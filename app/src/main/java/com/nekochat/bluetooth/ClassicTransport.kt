package com.nekochat.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.nekochat.R
import com.nekochat.chat.TransportType
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 蓝牙经典 RFCOMM（串口 SPP 风格）传输。
 *
 * - 服务端：`listenUsingInsecureRfcommWithServiceRecord` 监听固定 UUID，可接受多个客户端
 * - 客户端：`createInsecureRfcommSocketToServiceRecord` 连接已知 UUID 的设备
 *
 * 采用“不安全”socket 以避免每次连接都弹出配对确认；配对仍可在系统设置中完成。
 */
class ClassicTransport(private val context: Context) {

    companion object {
        /** 本应用自定义的 SPP 服务 UUID。 */
        val SERVICE_UUID: UUID = UUID.fromString("8f9c1a2e-3b4d-4e6f-9a10-5c7d8e9f0a11")
        private const val SDP_NAME = "NekoChatMesh"
    }

    /** 校验运行时权限，未授权时直接抛错避免 SecurityException 静默失败。 */
    private fun requirePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            if (connect != PackageManager.PERMISSION_GRANTED || scan != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException(context.getString(R.string.transport_no_bluetooth_permission))
            }
        } else {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            if (fine != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException(context.getString(R.string.transport_no_location_permission))
            }
        }
    }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** 已配对设备列表。 */
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasPermission()) return emptyList()
        return try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun hasPermission(): Boolean = try {
        requirePermission()
        true
    } catch (_: SecurityException) {
        false
    }

    /**
     * 启动监听循环。每次 accept 到一个连接都会回调 [onSocket]。
     * @return 用于停止监听的句柄
     */
    @SuppressLint("MissingPermission") // 已通过 requirePermission() 校验
    fun startServer(onSocket: (BluetoothSocket) -> Unit, onError: (String) -> Unit): Closeable {
        requirePermission()
        val adapter = adapter ?: throw IOException(context.getString(R.string.transport_bluetooth_unsupported))
        val serverSocket: BluetoothServerSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
            SDP_NAME,
            SERVICE_UUID
        )
        val stopped = AtomicBoolean(false)

        val thread = Thread({
            while (!stopped.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    if (!stopped.get()) onError(e.message ?: context.getString(R.string.transport_rfcomm_listen_interrupted))
                    break
                } ?: break
                onSocket(socket)
            }
            runCatching { serverSocket.close() }
        }, "rfcomm-server").apply {
            isDaemon = true
            start()
        }

        return Closeable {
            stopped.set(true)
            runCatching { serverSocket.close() }
            thread.interrupt()
        }
    }

    /** 主动连接远端设备，返回 RFCOMM 链路（阻塞调用，请在 IO 线程执行）。 */
    @SuppressLint("MissingPermission") // 已通过 requirePermission() 校验
    internal fun connect(device: BluetoothDevice): RfcommLink {
        requirePermission()
        val socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
        // 连接前停止扫描，否则 RFCOMM 建链成功率会显著下降
        runCatching { adapter?.cancelDiscovery() }
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        return RfcommLink(socket, context)
    }

    /** 把一个已连接的 socket 包装成 [PeerLink]。 */
    internal fun wrap(socket: BluetoothSocket): RfcommLink = RfcommLink(socket, context)
}

/** RFCOMM 链路的 [PeerLink] 实现。 */
internal class RfcommLink(
    private val socket: BluetoothSocket,
    /** 只用于取用户可见的断开原因文案。 */
    private val context: Context
) : PeerLink {

    override val address: String = socket.remoteDevice.address

    override val displayName: String = try {
        socket.remoteDevice.name ?: socket.remoteDevice.address
    } catch (_: SecurityException) {
        socket.remoteDevice.address
    }

    override val transport: TransportType = TransportType.RFCOMM

    @Volatile
    private var connected = true

    private val closedFired = AtomicBoolean(false)
    private var closeAction: (() -> Unit)? = null

    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream
    private val writeLock = Any()

    override val isConnected: Boolean
        get() = connected && socket.isConnected

    override fun write(frame: ByteArray, onComplete: (Boolean) -> Unit) {
        // 多线程（中继循环、心跳、握手）可能并发写同一 socket，必须串行化保证帧完整
        synchronized(writeLock) {
            try {
                output.write(frame)
                output.flush()
                onComplete(true)
            } catch (_: IOException) {
                connected = false
                onComplete(false)
                fireClosed(null)
            }
        }
    }

    override fun onClosed(action: () -> Unit) {
        closeAction = action
    }

    /** 阻塞读取循环，由 [ClassicTransport] 侧启动的线程调用。 */
    fun readLoop(onFrame: (ByteArray) -> Unit, onDown: (String?) -> Unit) {
        val header = ByteArray(4)
        try {
            while (connected) {
                readFully(header, 4)
                val len = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                if (len <= 0 || len > com.nekochat.chat.Wire.MAX_PAYLOAD) {
                    throw IOException(context.getString(R.string.transport_invalid_frame_len, len))
                }
                val body = ByteArray(len)
                readFully(body, len)
                onFrame(body)
            }
            onDown(null)
        } catch (e: Exception) {
            connected = false
            onDown(e.message)
        } finally {
            fireClosed(null)
        }
    }

    private fun readFully(target: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(target, read, length - read)
            if (n < 0) throw IOException(context.getString(R.string.transport_connection_closed))
            read += n
        }
    }

    private fun fireClosed(reason: String?) {
        connected = false
        if (closedFired.compareAndSet(false, true)) {
            closeAction?.invoke()
        }
    }

    override fun close() {
        connected = false
        runCatching { socket.close() }
        fireClosed(null)
    }
}
