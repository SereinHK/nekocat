package com.nekochat.bluetooth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.nekochat.R
import com.nekochat.chat.TransportType
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WiFi 局域网传输：TCP 长连接 + 复用同一套帧协议（4 字节长度前缀 + JSON）。
 *
 * 相比蓝牙的优势：无需配对、带宽高、距离远、连接数几乎无上限。
 * 代价：需要一个共同网络（同一路由器或热点）。
 *
 * 本类只负责**链路**，不负责发现。发现方式由上层决定：
 * 手动填 IP 最可靠，UDP 广播/mDNS 可后续再加。
 */
class WifiTransport(private val context: Context) {

    companion object {
        /** 日志标签。internal 以便同文件的 [TcpLink] 复用。 */
        internal const val TAG = "NekoChatWifi"

        /** 默认端口；用 0 则由系统分配空闲端口。 */
        const val DEFAULT_PORT = 45678

        /** 连接超时。 */
        private const val CONNECT_TIMEOUT_MS = 6_000
    }

    private var serverSocket: ServerSocket? = null

    /**
     * 本机在局域网中的 IPv4 地址。
     *
     * **优先用 `ConnectivityManager` 而不是枚举网卡。**
     * 真机教训：MatePad 上 `ip addr` 只有 `wlan0 = 192.168.31.5`，
     * 但 `NetworkInterface.getNetworkInterfaces()` 多返回了一个**已失效的
     * `192.168.31.45`**（ROM 里残留的接口）。取列表第一个正好取到它，
     * 于是界面显示、二维码里编码的都是一个连不上的地址。
     *
     * 注意 `LinkProperties` 在这种机器上**仍会同时报出两个地址**，
     * 所以调用方应当只用 [primaryAddress]（第一个 = 系统认定的主地址），
     * 不要把它们都展示给用户让他猜 —— 用户无从判断哪个能通。
     */
    fun localAddresses(): List<String> {
        val fromSystem = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching emptyList()
            cm.allNetworks
                .filter { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                .flatMap { network ->
                    cm.getLinkProperties(network)?.linkAddresses.orEmpty()
                        .mapNotNull { it.address }
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                }
        }.getOrDefault(emptyList())

        val usable = fromSystem.filter { isUsableLanAddress(it) }.distinct()
        if (usable.isNotEmpty()) return usable

        // 退路：系统没给出链路地址时再枚举网卡
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { isUsableLanAddress(it) }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** 排除回环，以及未拿到 DHCP 时自分配的链路本地地址（169.254.x.x）——对端连不上。 */
    private fun isUsableLanAddress(ip: String): Boolean =
        !ip.startsWith("127.") && !ip.startsWith("169.254.")

    /**
     * 应当展示给对端的地址 —— **只取一个**。
     *
     * 设备可能同时有多个有效地址（实测 MatePad 开了「双 WiFi 加速」，
     * `192.168.31.5` 与 `192.168.31.45` 都活着）。
     * 全都列出来只会让用户困惑：他没有依据判断该念哪个。
     *
     * @param reachableFrom 已知与某个对端互通的本地地址（由 [addressOnSameSubnetAs] 推出）。
     *        传入时会优先返回它 —— 这是最可靠的依据：**对方确实从那个地址连上来了**。
     * @return 没有可用地址时返回 **null**。
     *
     * 这里刻意返回可空类型，而不是 `"未连接 WiFi"` 这样的哨兵字符串：
     * 哨兵值会被上层当成真实地址拼接与展示（原实现下界面会显示
     * 「正在监听 未连接 WiFi:45678」，用户照着念或把二维码给对方都连不上）。
     * 「没有地址」是一种状态，不是一个地址，用类型表达才不会被误用。
     */
    fun primaryAddress(reachableFrom: String? = null): String? {
        val all = localAddresses()
        if (reachableFrom != null && all.contains(reachableFrom)) return reachableFrom
        return all.firstOrNull()
    }

    /**
     * 找出与 [peerIp] 处于同一网段的本地地址。
     *
     * 依据是「对端能从那个地址连进来」—— 比猜测系统当前把哪条链路当主链路可靠得多。
     *
     * @return 找不到同网段地址时返回 null
     */
    fun addressOnSameSubnetAs(peerIp: String?): String? {
        if (peerIp.isNullOrBlank()) return null
        return localAddresses().firstOrNull { sameSubnet24(it, peerIp) }
    }

    /** 是否属于同一个 /24 网段。家用局域网基本都是 /24，够用且不必猜掩码。 */
    private fun sameSubnet24(a: String, b: String): Boolean {
        val pa = a.split('.')
        val pb = b.split('.')
        if (pa.size != 4 || pb.size != 4) return false
        return pa[0] == pb[0] && pa[1] == pb[1] && pa[2] == pb[2]
    }

    /**
     * 供界面展示的本机地址列表；只保留最该展示的那一个。
     *
     * 没有可用地址时返回**空列表**，而不是含哨兵值的单元素列表 ——
     * 这样界面上「未获取到局域网地址」的提示分支才是活的，
     * 上层 `wifiEndpoint()` 的空值守卫也才有意义。
     */
    fun displayAddresses(): List<String> = listOfNotNull(primaryAddress())

    /**
     * 把 socket 绑定到 WiFi 网络。
     *
     * 不绑定的话，Android 在「WiFi 无外网」时会把流量交给蜂窝网络，
     * 去连局域网地址就会 **EHOSTUNREACH (No route to host)** —— 真机已复现。
     *
     * 注意**不能只用 `activeNetwork`**：它在 WiFi 无外网时可能指向蜂窝，
     * 那样绑定后反而更连不上。必须遍历所有网络，挑出**带 WiFi transport** 的那个。
     */
    private fun bindToWifiNetwork(socket: Socket) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching
            val wifi = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifi != null) {
                socket.bindToNetwork(wifi)
                Log.d(TAG, "socket 已绑定到 WiFi 网络")
            } else {
                Log.w(TAG, "未找到 WiFi 网络，使用系统默认路由")
            }
        }.onFailure { Log.w(TAG, "绑定 WiFi 网络失败：${it.message}") }
    }

    /** `Network.bindSocket` 的适配：绑定失败只记录，不阻断连接尝试。 */
    private fun Socket.bindToNetwork(network: Network) {
        network.bindSocket(this)
    }

    /**
     * 启动 TCP 服务端。
     *
     * @param port 监听端口，0 表示由系统分配（单机自测很有用）
     * @return 监听句柄，[WifiServerHandle.port] 是实际端口
     */
    fun startServer(
        port: Int = DEFAULT_PORT,
        onSocket: (Socket) -> Unit,
        onError: (String) -> Unit
    ): WifiServerHandle {
        val server = ServerSocket().apply {
            reuseAddress = true
            // 绑到所有网卡（含回环），这样同一台设备也能自连做验证
            bind(InetSocketAddress("0.0.0.0", port))
        }
        serverSocket = server
        val stopped = AtomicBoolean(false)
        val thread = Thread({
            while (!stopped.get()) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (!stopped.get()) onError(e.message ?: context.getString(R.string.transport_listen_interrupted))
                    break
                }
                runCatching {
                    // 关掉 Nagle：聊天消息小且要低延迟
                    socket.tcpNoDelay = true
                    onSocket(socket)
                }.onFailure { onError(it.message ?: context.getString(R.string.transport_handle_connection_failed)) }
            }
            runCatching { server.close() }
        }, "wifi-server").apply {
            isDaemon = true
            start()
        }

        return WifiServerHandle(server.localPort) {
            stopped.set(true)
            runCatching { server.close() }
            thread.interrupt()
            serverSocket = null
        }
    }

    fun stopServer() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** 连接到对端（阻塞，请在 IO 线程调用）。 */
    @Throws(IOException::class)
    internal fun connect(host: String, port: Int): TcpLink {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            bindToWifiNetwork(socket)
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        return TcpLink(socket, context)
    }
}

/** 服务端监听句柄。 */
class WifiServerHandle(
    /** 实际监听端口（传入 0 时由系统分配，需要读这个值才知道）。 */
    val port: Int,
    private val closeAction: () -> Unit
) : Closeable {
    override fun close() = closeAction()
}

/**
 * TCP 链路的 [PeerLink] 实现。
 *
 * 与 RFCOMM 版本结构一致：构造时拿到流，读循环逐帧解析（TCP 是字节流，
 * 用「读满 4 字节长度头 + 读满负载」的方式保证边界）。
 */
internal class TcpLink(
    private val socket: Socket,
    /** 只用于取用户可见的断开原因文案。 */
    private val context: Context
) : PeerLink {
    /** 用 ip:port 作为链路标识。同一设备自连时客户端/服务端两侧端口不同，不会冲突。 */
    override val address: String
        get() = "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"

    override val displayName: String
        get() = address

    override val transport: TransportType = TransportType.WIFI

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()

    @Volatile
    private var connected = true

    private val closedFired = AtomicBoolean(false)
    private var closeAction: (() -> Unit)? = null

    override val isConnected: Boolean
        get() = connected && !socket.isClosed

    override fun write(frame: ByteArray, onComplete: (Boolean) -> Unit) {
        // 多线程（中继/握手/心跳）可能并发写同一 socket，必须串行化保证帧完整
        synchronized(writeLock) {
            try {
                output.write(frame)
                output.flush()
                Log.w(WifiTransport.TAG, "已写 ${frame.size} 字节到 $address")
                onComplete(true)
            } catch (e: IOException) {
                Log.w(WifiTransport.TAG, "写失败 $address：${e.message}")
                connected = false
                onComplete(false)
                fireClosed()
            }
        }
    }

    override fun onClosed(action: () -> Unit) {
        closeAction = action
    }

    /** 阻塞读循环，逐帧解析。 */
    fun readLoop(onFrame: (ByteArray) -> Unit, onDown: (String?) -> Unit) {
        // 用 warn 级别：部分 ROM（实测 MatePad）会丢弃应用自身的 debug 日志，
        // 排查「连上但收不到数据」时这类生命周期日志必须可见。
        Log.w(WifiTransport.TAG, "readLoop 启动 ${address}")
        val header = ByteArray(4)
        try {
            while (connected) {
                readFully(header, 4)
                val len = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                if (len <= 0 || len > com.nekochat.chat.MAX_FRAME_PAYLOAD) {
                    throw IOException(context.getString(R.string.transport_invalid_frame_len, len))
                }
                val body = ByteArray(len)
                readFully(body, len)
                onFrame(body)
            }
            onDown(null)
        } catch (e: Exception) {
            Log.w(WifiTransport.TAG, "readLoop 结束 ${address}：${e.javaClass.simpleName} ${e.message}")
            connected = false
            onDown(e.message)
        } finally {
            fireClosed()
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

    private fun fireClosed() {
        connected = false
        if (closedFired.compareAndSet(false, true)) {
            closeAction?.invoke()
        }
    }

    override fun close() {
        connected = false
        runCatching { socket.close() }
        fireClosed()
    }
}
