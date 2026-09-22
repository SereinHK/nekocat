package com.nekochat.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.nekochat.R
import com.nekochat.chat.TransportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 低功耗蓝牙 (BLE) GATT 传输。
 *
 * 拓扑：每个节点同时运行 GATT Server（提供服务与通知）与 GATT Client（主动连接对端）。
 * 数据方向：
 *  - 发：写入对端服务的 RX 特征值
 *  - 收：本地服务 TX 特征值的通知（notify）
 *
 * 因为一个 GATT Server 可以同时向多个已连接的 Central 发通知，
 * 所以两个节点互相连接时天然形成双向通道；再叠加聊天层的去重即可组网。
 */
class BleTransport(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6d5a1f30-2c4b-4a9e-8f21-7b6c5d4e3f22")
        val CHAR_TX_UUID: UUID = UUID.fromString("6d5a1f31-2c4b-4a9e-8f21-7b6c5d4e3f22")
        val CHAR_RX_UUID: UUID = UUID.fromString("6d5a1f32-2c4b-4a9e-8f21-7b6c5d4e3f22")
        internal val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** 单次写入的分片大小上限，留足余量以适配 23 字节默认 MTU 到 517 字节最大 MTU。 */
        internal const val CHUNK_SIZE = 160

        /** ATT 默认 MTU。协商结果出来之前只能按它算单次能发多少。 */
        internal const val DEFAULT_ATT_MTU = 23

        /** 单次传输最少能带的负载（MTU 23 时的 20 字节），再小就没法协商了。 */
        private const val MIN_CHUNK = 20

        /** BLE 规范允许的最大 ATT MTU。 */
        private const val MAX_ATT_MTU = 517

        /**
         * 一次 GATT 传输最多能带多少负载字节 —— **必须按协商出来的 MTU 算**。
         *
         * ATT 每次读写受 MTU 限制，可用负载是 `MTU − 3`（1 字节操作码 + 2 字节句柄），
         * 超出部分协议栈直接拒绝。以前这里写死 160 字节、两处 `onMtuChanged`
         * 都只打日志不落地，于是真机上「发一条长消息，发完就掉线」：
         * 分片超过对端能收的长度，写失败，而写失败会主动断开链路。
         *
         * 短消息能过只是因为没超过那个长度，属于运气。
         *
         * 先把 MTU 夹到合法区间再减 3：直接对任意 Int 做减法会在 `Int.MIN_VALUE`
         * 附近溢出成正数，反而算出最大的分片。
         */
        internal fun chunkSizeFor(mtu: Int): Int =
            (mtu.coerceIn(DEFAULT_ATT_MTU, MAX_ATT_MTU) - 3).coerceIn(MIN_CHUNK, CHUNK_SIZE)


        const val MANUFACTURER_ID = 0x02E5

        /**
         * 广播里携带「拨号标识」所用的 16 位服务数据 UUID。
         *
         * 拨号标识是首次相遇时双方**唯一共有的可比量**，用来决定谁主动拨号
         * （见 `MeshManager.shouldDial`）。它放在**广播包**而不是扫描响应包里：
         * 广播包目前只用了 21 字节（flags 3 + 128 位服务 UUID 18），
         * 还剩 10 字节，正好塞得下 4 字节 AD 头 + [ADV_TOKEN_BYTES] 字节标识。
         */
        val ADV_TOKEN_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

        /** 拨号标识的字节数上限（受广播包 31 字节总长限制）。 */
        const val ADV_TOKEN_BYTES = 6

        /** 服务端链路建立后，延迟这么久再发首帧，等对端订阅完 CCCD 通知。 */
        private const val HANDSHAKE_SETTLE_MS = 700L

        internal const val TAG = "NekoChatBle"

        /** 单次 BLE 握手超时。 */
        private const val CONNECT_TIMEOUT_MS = 8_000L

        /** 握手失败后的重试轮数与间隔。 */
        private const val CONNECT_ROUNDS = 3
        private const val HANDSHAKE_RETRY_DELAY_MS = 1_200L
    }

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun requirePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            val advertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
            if (connect != PackageManager.PERMISSION_GRANTED ||
                scan != PackageManager.PERMISSION_GRANTED ||
                advertise != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException(context.getString(R.string.transport_no_ble_permission))
            }
        } else {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            if (fine != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException(context.getString(R.string.transport_no_location_permission))
            }
        }
    }

    // ---------------------------------------------------------------------
    // GATT Server
    // ---------------------------------------------------------------------

    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    /** 当前已连接（且已订阅通知）的 Central，key 为设备地址。 */
    private val centrals = ConcurrentHashMap<String, BluetoothDevice>()

    /** 各 Central 的通知订阅状态，key 为设备地址。 */
    private val subscribedCentrals = ConcurrentHashMap<String, Boolean>()

    /** 各 Central 协商出来的 ATT MTU，key 为设备地址。未协商前按 [DEFAULT_ATT_MTU] 算。 */
    private val mtuByDevice = ConcurrentHashMap<String, Int>()

    /**
     * 每个 Central 的待发通知队列。
     *
     * 一帧长消息有上千字节，而单次 notify 最多只能带 `MTU − 3` 字节，必须切片发。
     * 切片又不能连着甩出去 —— 上一片还没发完就发下一片会被协议栈判成忙而丢包，
     * 所以一次只发一片，等 [BluetoothGattServerCallback.onNotificationSent] 回来再发下一片。
     */
    private class NotifySlice(val payload: ByteArray, val onComplete: ((Boolean) -> Unit)?)

    private val notifyLock = Any()
    private val notifyQueues = HashMap<String, ArrayDeque<NotifySlice>>()

    /** 每个 Central 当前正在发的那一片，收到 onNotificationSent 后据此取回调。 */
    private val notifyInFlight = HashMap<String, NotifySlice>()

    private var serverListener: TransportListener? = null
    private val advertised = AtomicBoolean(false)

    @SuppressLint("MissingPermission") // 已在 startServer/startScan 入口处校验权限
    private inner class ServerCallback : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                centrals[address] = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                centrals.remove(address)
                subscribedCentrals.remove(address)
                mtuByDevice.remove(address)
                // 队列里剩下的分片不可能再发出去了，全部判失败，避免回调永远不触发
                val dropped = ArrayList<NotifySlice>()
                synchronized(notifyLock) {
                    notifyInFlight.remove(address)?.let { dropped += it }
                    notifyQueues.remove(address)?.let { dropped.addAll(it) }
                }
                dropped.forEach { it.onComplete?.invoke(false) }
                serverListener?.let { listener ->
                    serverLinks.remove(address)?.let { listener.onLinkDown(it, context.getString(R.string.transport_peer_disconnected)) }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            // 协商结果必须落地：分片大小按它算，否则长帧会超出单次传输上限
            mtuByDevice[device.address] = mtu
            Log.i(TAG, "服务端 MTU 协商 addr=${device.address} mtu=$mtu 单片上限=${chunkSizeFor(mtu)}")
        }

        /** 上一片通知发完了，取回调并接着发下一片。 */
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val address = device.address
            val done: NotifySlice?
            synchronized(notifyLock) { done = notifyInFlight.remove(address) }
            if (done != null) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "通知发送失败 addr=$address status=$status")
                }
                done.onComplete?.invoke(status == BluetoothGatt.GATT_SUCCESS)
            }
            pumpNotifications(device)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.d(
                TAG,
                "onCharacteristicWriteRequest addr=${device.address} char=${characteristic.uuid.toString().take(8)} " +
                    "size=${value?.size ?: -1} responseNeeded=$responseNeeded"
            )
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
            if (characteristic.uuid != CHAR_RX_UUID || value == null) {
                Log.d(TAG, "忽略：不是 RX 特征值或 value 为空")
                return
            }

            // 链路在 CCCD 订阅完成后建立；此处兜底以防订阅回调缺失
            val link = serverLinks[device.address] ?: createServerLink(device)
            if (link == null) {
                Log.w(TAG, "无法创建服务端链路，数据丢弃")
                return
            }
            link.deliver(value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
            // Central 写入 CCCD 即表示订阅/取消订阅了通知
            if (descriptor.uuid == CCCD_UUID && value != null) {
                val enabled = value.isNotEmpty() && value[0] != 0.toByte()
                Log.i(TAG, "onDescriptorWriteRequest addr=${device.address} 订阅=$enabled")
                subscribedCentrals[device.address] = enabled
                // 订阅成功才建立逻辑链路：这样后续写入必定能被对端收到，
                // 也避免在「已连接但收不到数据」的窗口里把首帧 HELLO 丢掉。
                if (enabled) createServerLink(device)
            }
        }
    }

    /** 服务端侧的对等链路（由本机 GATT Server 向对端发通知）。 */
    private val serverLinks = ConcurrentHashMap<String, BleServerLink>()

    /**
     * 启动 GATT Server 并开始广播，允许其他节点主动连接本机。
     *
     * @param advToken 随广播发出的拨号标识，为空则不附带该字段
     */
    @SuppressLint("MissingPermission")
    fun startServer(listener: TransportListener, localName: String, advToken: String = ""): Boolean {
        requirePermission()
        val adapter = adapter
        if (adapter == null) {
            Log.w(TAG, "无法获取 BluetoothAdapter")
            return false
        }
        if (gattServer != null) return true

        val tx = BluetoothGattCharacteristic(
            CHAR_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        val rx = BluetoothGattCharacteristic(
            CHAR_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(tx)
            addCharacteristic(rx)
        }

        serverListener = listener
        // API 37 起 openGattServer 由 BluetoothManager 提供
        val manager = bluetoothManager
        if (manager == null) {
            Log.w(TAG, "无法获取 BluetoothManager")
            return false
        }
        val server = manager.openGattServer(context, ServerCallback())
        if (server == null) {
            Log.w(TAG, "openGattServer 返回 null")
            return false
        }
        if (!server.addService(service)) {
            Log.w(TAG, "addService 失败")
            runCatching { server.close() }
            return false
        }
        gattServer = server
        txCharacteristic = tx
        Log.i(TAG, "GATT 服务已注册，准备开始广播")

        // 开始广播，携带服务 UUID 与短名称，便于对端识别。
        // 每一步都记日志：真机上出现过「GATT 服务注册成功但广播没起来」的情况
        // （平板端 mAdvertisingServiceUuids 为空），没有日志就无法判断卡在哪一步。
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "无法获取 BluetoothLeAdvertiser，本机不支持 BLE 广播")
            return true
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
        if (advToken.isNotEmpty()) {
            dataBuilder.addServiceData(
                ParcelUuid(ADV_TOKEN_UUID),
                advToken.toByteArray(Charsets.US_ASCII).take(ADV_TOKEN_BYTES).toByteArray()
            )
        }
        val data = dataBuilder.build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addManufacturerData(MANUFACTURER_ID, localName.toByteArray(Charsets.UTF_8).take(24).toByteArray())
            .build()

        return try {
            advertiser.startAdvertising(settings, data, scanResponse, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    advertised.set(true)
                    Log.i(TAG, "BLE 广播已启动 (mode=${settingsInEffect.mode}, txPower=${settingsInEffect.txPowerLevel})")
                }

                override fun onStartFailure(errorCode: Int) {
                    advertised.set(false)
                    Log.w(TAG, "BLE 广播启动失败 errorCode=$errorCode（1=DATA_TOO_LARGE 2=TOO_MANY_ADVERTISERS 3=ALREADY_STARTED 4=INTERNAL_ERROR 5=FEATURE_UNSUPPORTED）")
                }
            })
            Log.d(TAG, "startAdvertising 已调用")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startAdvertising 缺权限：${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising 异常：${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(null) }
        advertised.set(false)
        serverLinks.values.forEach { it.close() }
        serverLinks.clear()
        centrals.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        txCharacteristic = null
        serverListener = null
    }

    // ---------------------------------------------------------------------
    // GATT Client
    // ---------------------------------------------------------------------

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientLinks = ConcurrentHashMap<String, BleClientLink>()

    /** 对端是否已主动连上本机（此时本机不该再向它拨号，否则双主角色冲突）。 */
    fun isPeerConnected(address: String): Boolean = serverLinks.containsKey(address)

    /**
     * 连接远端设备（异步，带超时与自动重试）。
     *
     * 单次握手必须限时：`connectGatt` 返回非 null 只代表请求已受理，
     * 若对端服务发现失败或 CCCD 写入被拒，不设超时就会永远停在中间态。
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, listener: TransportListener, onLink: (PeerLink?) -> Unit) {
        val adapter = adapter ?: run { onLink(null); return }
        clientLinks[device.address]?.let { existing ->
            onLink(existing)
            return
        }
        val link = BleClientLink(device, listener, clientScope, context)
        clientLinks[device.address] = link

        clientScope.launch {
            var reason = "未知原因"
            for (round in 1..CONNECT_ROUNDS) {
                // 对端可能在本机重试期间主动连了上来，此时改用服务端链路，避免双主冲突
                if (serverLinks.containsKey(device.address)) {
                    Log.i(TAG, "放弃拨号 ${device.address}：对端已主动连上本机")
                    runCatching { link.close() }
                    clientLinks.remove(device.address, link)
                    return@launch
                }
                when (val result = link.handshake(CONNECT_TIMEOUT_MS)) {
                    is BleClientLink.HandshakeResult.Success -> {
                        Log.i(TAG, "BLE 握手成功 ${device.address}（第 $round 轮）")
                        withContext(Dispatchers.Main) { onLink(link) }
                        return@launch
                    }

                    is BleClientLink.HandshakeResult.Failure -> {
                        reason = result.reason
                        Log.w(TAG, "BLE 握手失败 ${device.address} 第 $round 轮：$reason")
                        runCatching { link.close() }
                        if (round < CONNECT_ROUNDS) delay(HANDSHAKE_RETRY_DELAY_MS)
                    }
                }
            }
            clientLinks.remove(device.address, link)
            Log.w(TAG, "放弃连接 ${device.address}：$reason")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        clientLinks.remove(address)?.close()
        serverLinks.remove(address)?.close()
    }

    /**
     * 服务端侧向某个 Central 建立逻辑链路。由 [ServerCallback.onConnectionStateChange] 在
     * Central 一连上时调用（此时 CCCD 订阅可能尚未完成，因此稍作延迟再发首帧 HELLO）。
     */
    private fun createServerLink(device: BluetoothDevice): BleServerLink? {
        val listener = serverListener ?: return null
        val writer: BleWriteSink = { payload, onComplete ->
            // 对端尚未订阅通知时直接返回 false，让上层知道这帧没真正送达
            // （系统在这种情况下也可能返回 SUCCESS，不能只信返回值）
            if (subscribedCentrals[device.address] == true) {
                enqueueNotification(device, payload, onComplete)
            } else {
                onComplete(false)
            }
        }
        val existing = serverLinks[device.address]
        if (existing != null) return existing
        val link = BleServerLink(device, listener, writer, context)
        val previous = serverLinks.putIfAbsent(device.address, link)
        if (previous != null) return previous
        clientScope.launch {
            // 给对端留出完成服务发现 + 订阅通知的时间，否则首帧会被吞掉
            kotlinx.coroutines.delay(HANDSHAKE_SETTLE_MS)
            listener.onLinkUp(link)
        }
        return link
    }

    /**
     * 把一整帧按当前 MTU 切片排队发给某个 Central。
     *
     * 只有最后一片带上整帧的回调 —— 前面的片失败了会把队列里剩下的都判失败，
     * 不会出现「回调永远不触发」。
     */
    internal fun enqueueNotification(
        device: BluetoothDevice,
        frame: ByteArray,
        onComplete: (Boolean) -> Unit
    ) {
        val size = chunkSizeFor(mtuByDevice[device.address] ?: DEFAULT_ATT_MTU)
        val slices = ArrayList<NotifySlice>()
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + size, frame.size)
            slices += NotifySlice(frame.copyOfRange(offset, end), null)
            offset = end
        }
        if (slices.isEmpty()) {
            onComplete(true)
            return
        }
        // 只有最后一片的回调代表「整帧发完」
        slices[slices.lastIndex] = NotifySlice(slices[slices.lastIndex].payload, onComplete)
        if (slices.size > 1) {
            Log.d(TAG, "通知分 ${slices.size} 片发送 ${frame.size} 字节（单片 $size）")
        }

        synchronized(notifyLock) {
            notifyQueues.getOrPut(device.address) { ArrayDeque() }.addAll(slices)
        }
        pumpNotifications(device)
    }

    private fun pumpNotifications(device: BluetoothDevice) {
        val address = device.address
        val slice: NotifySlice
        synchronized(notifyLock) {
            if (notifyInFlight.containsKey(address)) return   // 上一片还没发完
            val queue = notifyQueues[address] ?: return
            val next = queue.removeFirstOrNull()
            if (next == null) {
                notifyQueues.remove(address)
                return
            }
            notifyInFlight[address] = next
            slice = next
        }
        if (sendNotificationNow(device, slice.payload)) return

        // 没受理：这一片和队列里剩下的一次性判失败，否则回调永远等不到
        val rest = ArrayList<NotifySlice>()
        synchronized(notifyLock) {
            notifyInFlight.remove(address)
            notifyQueues.remove(address)?.let { rest.addAll(it) }
        }
        slice.onComplete?.invoke(false)
        rest.forEach { it.onComplete?.invoke(false) }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotificationNow(device: BluetoothDevice, payload: ByteArray): Boolean {
        val server = gattServer ?: return false
        val tx = txCharacteristic ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, tx, false, payload) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                tx.value = payload
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, tx, false)
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // 扫描
    // ---------------------------------------------------------------------

    private var scanCallback: ScanCallback? = null

    /**
     * 开始 BLE 扫描，**只上报广播了本应用服务 UUID 的设备**。
     *
     * 过滤是必须的：不加过滤器会把附近所有蓝牙设备（耳机、音箱、家电……）都报上来，
     * 上层会挨个尝试建立 GATT 连接然后失败重试，既费电又刷屏。
     *
     * @param onFound 回调 (设备, 信号强度, 广播名称, 拨号标识)
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        onFound: (BluetoothDevice, Int, String?, String?) -> Unit,
        onError: (String) -> Unit,
        useServiceFilter: Boolean = false
    ) {
        requirePermission()
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onError(context.getString(R.string.transport_ble_scan_unsupported))
            return
        }
        stopScan()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                // 始终在应用层校验服务 UUID。
                //
                // 真机教训：仅依赖 ScanFilter(setServiceUuid) 会**一条结果都收不到**
                // ——手机的 AppScanStats 已累计上报 800+ 条扫描结果，但回调一次都没触发，
                // 因为对端（HarmonyOS）的广播包与过滤条件不匹配。
                // 所以过滤器只作为省电的优化，正确性由这里保证。
                val advertises = record != null && advertisesChatService(record)
                if (!advertises) {
                    if (!useServiceFilter) {
                        skipCount++
                        if (skipCount % 500 == 1) {
                            Log.d(TAG, "已扫描 ${skipCount} 个无关广播，暂未发现聊天节点")
                        }
                    }
                    return
                }
                Log.i(TAG, "发现聊天节点 ${result.device.address} rssi=${result.rssi}")
                val name = record.deviceName
                    ?: runCatching { result.device.name }.getOrNull()
                onFound(result.device, result.rssi, name, readAdvToken(record))
            }

            override fun onScanFailed(errorCode: Int) {
                onError(context.getString(R.string.transport_scan_failed, errorCode))
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            if (useServiceFilter) {
                scanner.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                    settings,
                    callback
                )
            } else {
                scanner.startScan(null, settings, callback)
            }
        } catch (e: SecurityException) {
            scanCallback = null
            onError(context.getString(R.string.transport_no_scan_permission))
        }
    }

    /** 本轮扫描中被应用层过滤掉的无关广播数量，仅用于日志。 */
    @Volatile
    private var skipCount = 0

    /** 判断扫描记录中是否包含本应用的聊天服务 UUID（广播包或扫描响应包均可）。 */
    @Suppress("DEPRECATION")
    private fun advertisesChatService(record: android.bluetooth.le.ScanRecord): Boolean {
        val uuids = record.serviceUuids ?: return false
        return uuids.any { it.uuid == SERVICE_UUID }
    }

    /** 读取广播包中的拨号标识；对端未附带该字段（旧版本）时返回 null。 */
    private fun readAdvToken(record: android.bluetooth.le.ScanRecord?): String? =
        record?.getServiceData(ParcelUuid(ADV_TOKEN_UUID))
            ?.toString(Charsets.US_ASCII)
            ?.takeIf { it.isNotEmpty() }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    /** 释放客户端资源。 */
    fun shutdown() {
        stopScan()
        clientLinks.values.forEach { it.close() }
        clientLinks.clear()
        stopServer()
    }
}

/**
 * 服务端侧链路：通过 GATT Server 的 notify 发送数据。
 *
 * 通过构造参数注入写入实现，避免链路反向依赖 [BleTransport] 造成初始化顺序问题。
 */
internal class BleServerLink(
    private val device: BluetoothDevice,
    private val listener: TransportListener,
    private val writer: BleWriteSink,
    /** 只用于取用户可见的断开原因文案。 */
    private val context: Context
) : PeerLink {

    private val closed = AtomicBoolean(false)
    private var closeAction: (() -> Unit)? = null

    override val address: String = device.address

    override val displayName: String
        @SuppressLint("MissingPermission")
        get() = runCatching { device.name ?: device.address }.getOrDefault(device.address)

    override val transport: TransportType = TransportType.BLE

    override val isConnected: Boolean
        get() = !closed.get()

    override fun write(frame: ByteArray, onComplete: (Boolean) -> Unit) {
        writer(frame) { ok ->
            if (!ok) {
                // 整帧（含全部分片）发完才判定链路失效。
                // 以前是整帧一次 notify，长消息必然超过单次传输上限而失败，
                // 于是表现为「发一条长消息就掉线」。
                closed.set(true)
                fireClosed(context.getString(R.string.transport_ble_notify_failed))
            }
            onComplete(ok)
        }
    }

    /**
     * 服务端侧的帧解码器。
     *
     * **必须做分片重组**：客户端写入是按 [BleTransport.CHUNK_SIZE] 分片的，
     * GATT Server 每收到一个分片就回调一次 `onCharacteristicWriteRequest`。
     * 如果把每个分片直接当整帧交给上层，只有第一片带长度头、其余都是裸负载，
     * 上层会报「收到无法解析的数据包」——真机已复现。
     * 客户端侧本来就有 FrameDecoder，这里补齐服务端侧。
     */
    private val decoder = com.nekochat.chat.FrameDecoder()

    /** 收到对端写入的数据分片（由 GATT Server 回调触发）。 */
    fun deliver(payload: ByteArray) {
        // 诊断：打印分片大小与前几字节，确认分片重组是否符合预期
        if (payload.size >= 4) {
            val declared = ((payload[0].toInt() and 0xFF) shl 24) or
                ((payload[1].toInt() and 0xFF) shl 16) or
                ((payload[2].toInt() and 0xFF) shl 8) or
                (payload[3].toInt() and 0xFF)
            Log.d(BleTransport.TAG, "服务端收到分片 size=${payload.size} 声明的帧长=$declared")
        } else {
            Log.d(BleTransport.TAG, "服务端收到分片 size=${payload.size} (不足 4 字节，待拼接)")
        }
        // FrameDecoder 直接产出解析好的 Envelope，而上层接口要的是「去掉长度头的负载字节」，
        // 所以重新编码一次再去掉前 4 字节。
        val frames = decoder.feed(payload)
        if (frames.isEmpty()) {
            Log.d(BleTransport.TAG, "本分片尚未组成完整帧，继续缓存")
        }
        for (envelope in frames) {
            val frame = com.nekochat.chat.Wire.encode(envelope)
            listener.onFrame(this, frame.copyOfRange(4, frame.size))
        }
    }

    override fun onClosed(action: () -> Unit) {
        closeAction = action
    }

    private fun fireClosed(reason: String?) {
        if (closed.compareAndSet(false, true)) {
            closeAction?.invoke()
            listener.onLinkDown(this, reason)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeAction?.invoke()
        }
    }
}

/** BLE 写入实现：把一帧负载发送到指定设备，发完（含全部分片）回调结果。 */
internal typealias BleWriteSink = (ByteArray, (Boolean) -> Unit) -> Unit

/**
 * 客户端侧链路：作为 Central 连接对端 GATT Server。
 */
internal class BleClientLink(
    private val device: BluetoothDevice,
    private val listener: TransportListener,
    private val scope: CoroutineScope,
    /** 只用于取用户可见的断开原因文案。 */
    private val context: Context
) : PeerLink {

    /** 一次握手尝试的结果。 */
    sealed interface HandshakeResult {
        data object Success : HandshakeResult
        data class Failure(val reason: String) : HandshakeResult
    }

    private val closed = AtomicBoolean(false)
    private var closeAction: (() -> Unit)? = null
    private val writeMutex = Mutex()
    private val pendingWrite = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val writeCounterLock = Any()
    private var writeSeq = 0
    private var lastWriteSeq = -1

    /** 握手轮次计数，用于丢弃上一轮遗留的 GATT 回调。 */
    private val roundLock = Any()
    private var roundId = 0

    @Volatile
    private var currentRound = 0

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var txChar: BluetoothGattCharacteristic? = null

    /**
     * 对端 GATT 服务里负责**接收**数据的特征值（本机作为客户端时往这里写）。
     *
     * 必须和 [txChar] 分开：TX 只有 NOTIFY/READ，只能用来订阅和接收；
     * 往 TX 上写会被对端协议栈直接以「Write Not Permitted」拒绝，
     * 数据一个字节都到不了对端，而本地看起来链路一切正常。
     */
    @Volatile
    private var rxChar: BluetoothGattCharacteristic? = null

    /** 与本机协商出来的 ATT MTU。没协商出来之前按默认值算，宁可慢也不能超。 */
    @Volatile
    private var negotiatedMtu = BleTransport.DEFAULT_ATT_MTU

    @Volatile
    private var connected = false

    /**
     * 各阶段完成信号，供 [handshake] 逐步等待。
     *
     * 必须在**每一轮握手开始时重建**：这些是单次性的 [CompletableDeferred]，
     * 若沿用同一个实例，第一轮 complete 之后后续轮次会立刻返回陈旧结果，
     * 导致重试形同虚设。
     */
    private var discovered = CompletableDeferred<Boolean>()
    private var mtuSettled = CompletableDeferred<Unit>()
    private var subscribed = CompletableDeferred<Boolean>()

    /** 每一轮握手重新建立阶段信号。 */
    private fun resetSignals() {
        synchronized(roundLock) {
            roundId++
            discovered = CompletableDeferred()
            mtuSettled = CompletableDeferred()
            subscribed = CompletableDeferred()
        }
    }

    /**
     * 判断回调是否属于当前轮次；陈旧轮次的事件必须丢弃，否则会污染新一轮的阶段信号。
     *
     * 用轮次序号而不是 GATT 对象身份比较：连接回调可能早于 `gatt = g` 赋值到达，
     * 用对象身份会把当前轮误判成陈旧回调。
     */
    private fun isCurrentRound(g: BluetoothGatt): Boolean {
        val current = synchronized(roundLock) { roundId }
        return currentRound == current || g === gatt
    }

    override val address: String = device.address

    override val displayName: String
        @SuppressLint("MissingPermission")
        get() = runCatching { device.name ?: device.address }.getOrDefault(device.address)

    override val transport: TransportType = TransportType.BLE

    override val isConnected: Boolean
        get() = connected && !closed.get()

    private val callback = object : android.bluetooth.BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(BleTransport.TAG, "onConnectionStateChange status=$status newState=$newState ${device.address}")
            if (!isCurrentRound(g)) {
                runCatching { g.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val ok = runCatching { g.discoverServices() }.getOrDefault(false)
                    if (!ok) discovered.complete(false)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    discovered.complete(false)
                    subscribed.complete(false)
                    fireClosed(context.getString(R.string.transport_peer_disconnected))
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!isCurrentRound(g)) return
            val service = g.getService(BleTransport.SERVICE_UUID)
            val tx = service?.getCharacteristic(BleTransport.CHAR_TX_UUID)
            val rx = service?.getCharacteristic(BleTransport.CHAR_RX_UUID)
            Log.d(
                BleTransport.TAG,
                "onServicesDiscovered status=$status service=${service != null} tx=${tx != null} rx=${rx != null} " +
                    "services=${g.services?.size ?: 0}"
            )
            if (status != BluetoothGatt.GATT_SUCCESS || service == null || tx == null) {
                discovered.complete(false)
                return
            }
            if (rx == null) {
                // 没有 RX 就只能收不能发，链路是残废的，早点报错好过「显示在线但发不出去」
                Log.w(BleTransport.TAG, "对端 ${device.address} 的 GATT 服务里没有 RX 特征值，无法发送数据")
                discovered.complete(false)
                return
            }
            txChar = tx
            rxChar = rx
            discovered.complete(true)
            // 后续写入交由 handshake() 按顺序驱动，避免多个 GATT 操作同时在途
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentRound(g)) return
            // 协商结果要落地：分片大小按它算，否则长帧的分片会超过对端能收的长度
            negotiatedMtu = mtu
            Log.i(BleTransport.TAG, "客户端 MTU 协商 mtu=$mtu status=$status 单片上限=${BleTransport.chunkSizeFor(mtu)}")
            mtuSettled.complete(Unit)
        }

        /**
         * 订阅确认。只有走到这里链路才算真正可用：
         * 早于此刻写入数据会被对端静默丢弃。
         */
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != BleTransport.CCCD_UUID) return
            if (!isCurrentRound(g)) return
            Log.d(BleTransport.TAG, "onDescriptorWrite(CCCD) status=$status")
            subscribed.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        /** API 33+：分片回调（服务端写入分片时触发）。 */
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleTransport.CHAR_TX_UUID) {
                listener.onFrame(this@BleClientLink, value)
            }
        }

        /** API < 33 的旧回调。 */
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == BleTransport.CHAR_TX_UUID) {
                val value = characteristic.value ?: return
                listener.onFrame(this@BleClientLink, value)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 写错特征值（比如写到只读的 TX 上）时会走到这里，光看链路状态是发现不了的
                Log.w(
                    BleTransport.TAG,
                    "onCharacteristicWrite 失败 status=$status char=${characteristic.uuid.toString().take(8)} " +
                        "${device.address}（3=WRITE_NOT_PERMITTED 133=GATT_ERROR 200=WRITE_REQUEST_BUSY）"
                )
            }
            synchronized(writeCounterLock) {
                pendingWrite.remove(lastWriteSeq)?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    /**
     * 执行一次完整握手：连接 → 服务发现 → 请求 MTU → 订阅通知。
     *
     * 严格按顺序推进，因为 BLE 的 GATT 操作同一时刻只允许一个在途，
     * 连着发多个请求会被系统以 `ERROR_GATT_WRITE_REQUEST_BUSY` 拒绝。
     *
     * @param timeoutMs 整轮握手的超时上限
     */
    @SuppressLint("MissingPermission")
    suspend fun handshake(timeoutMs: Long): HandshakeResult {
        resetSignals() // 每轮重建信号，否则重试会立即拿到上一轮的陈旧结果
        currentRound = synchronized(roundLock) { roundId }
        val result = withTimeoutOrNull(timeoutMs) {
            val g = runCatching {
                device.connectGatt(null, false, callback, BluetoothDevice.TRANSPORT_LE)
            }.getOrNull() ?: return@withTimeoutOrNull HandshakeResult.Failure("connectGatt 返回 null")
            gatt = g

            // 1. 服务发现
            if (discovered.await() != true) {
                return@withTimeoutOrNull HandshakeResult.Failure("服务发现失败：对端未提供目标服务或特征值")
            }
            val tx = txChar ?: return@withTimeoutOrNull HandshakeResult.Failure("TX 特征值缺失")

            // 2. 请求更大 MTU（失败不致命，分片会自适应）
            if (runCatching { g.requestMtu(512) }.getOrDefault(false)) {
                withTimeoutOrNull(2_000L) { mtuSettled.await() }
            }

            // 3. 开启本地通知开关
            if (!runCatching { g.setCharacteristicNotification(tx, true) }.getOrDefault(false)) {
                return@withTimeoutOrNull HandshakeResult.Failure("setCharacteristicNotification 失败")
            }

            // 4. 写 CCCD 让对端开始 notify —— 必须等上一步落地后再发
            val cccd = tx.getDescriptor(BleTransport.CCCD_UUID)
                ?: return@withTimeoutOrNull HandshakeResult.Failure("对端 TX 特征值没有 CCCD")
            val submitted = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }.getOrDefault(false)
            if (!submitted) {
                return@withTimeoutOrNull HandshakeResult.Failure("writeDescriptor(CCCD) 未被受理")
            }

            // 5. 等订阅确认
            if (subscribed.await() != true) {
                return@withTimeoutOrNull HandshakeResult.Failure("CCCD 订阅被拒绝")
            }
            connected = true
            HandshakeResult.Success
        }
        if (result == null) {
            runCatching { gatt?.close() }
            gatt = null
            return HandshakeResult.Failure("握手超时（${timeoutMs}ms）")
        }
        if (result is HandshakeResult.Failure) {
            runCatching { gatt?.close() }
            gatt = null
        }
        return result
    }

    @SuppressLint("MissingPermission") // 写入前已确认链路处于连接状态
    override fun write(frame: ByteArray, onComplete: (Boolean) -> Unit) {
        val g = gatt
        // 写的是 RX（对端用来收的特征值），不是用来订阅通知的 TX
        val tx = rxChar
        if (g == null || tx == null || !isConnected) {
            // 这里以前是静默返回：链路看起来正常却一个字节都发不出去，极难排查
            Log.w(
                BleTransport.TAG,
                "写入丢弃（gatt=${g != null} rx=${tx != null} connected=$isConnected）${device.address}"
            )
            onComplete(false)
            return
        }
        scope.launch {
            writeMutex.withLock {
                // 分片大小按协商到的 MTU 算，不写死 —— 见 chunkSizeFor 的注释
                val chunkSize = BleTransport.chunkSizeFor(negotiatedMtu)
                if (frame.size > chunkSize) {
                    Log.d(
                        BleTransport.TAG,
                        "分 ${(frame.size + chunkSize - 1) / chunkSize} 片发送 ${frame.size} 字节（MTU=$negotiatedMtu）"
                    )
                }
                var offset = 0
                var success = true
                while (offset < frame.size) {
                    val end = minOf(offset + chunkSize, frame.size)
                    val chunk = frame.copyOfRange(offset, end)
                    val seq: Int
                    val deferred = CompletableDeferred<Boolean>()
                    synchronized(writeCounterLock) {
                        seq = writeSeq++
                        lastWriteSeq = seq
                        pendingWrite[seq] = deferred
                    }
                    val submitted = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeCharacteristic(
                                tx,
                                chunk,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            ) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            tx.value = chunk
                            @Suppress("DEPRECATION")
                            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(tx)
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (!submitted) {
                        pendingWrite.remove(seq)
                        success = false
                        break
                    }
                    // 等 onCharacteristicWrite 回调确认落盘；超时放宽到 4s 以覆盖冷启动首包的慢路径
                    val acked = try {
                        kotlinx.coroutines.withTimeout(4000L) { deferred.await() }
                    } catch (_: Exception) {
                        // 超时不视为致命错误，继续发送后续分片
                        true
                    } finally {
                        pendingWrite.remove(seq)
                    }
                    if (!acked) {
                        success = false
                        break
                    }
                    offset = end
                }
                onComplete(success)
                if (!success) handleWriteFailure()
            }
        }
    }

    private fun handleWriteFailure() {
        connected = false
        fireClosed(context.getString(R.string.transport_ble_send_failed))
    }

    override fun onClosed(action: () -> Unit) {
        closeAction = action
    }

    private fun fireClosed(reason: String?) {
        if (closed.compareAndSet(false, true)) {
            connected = false
            closeAction?.invoke()
            listener.onLinkDown(this, reason)
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        if (closed.compareAndSet(false, true)) {
            closeAction?.invoke()
        }
    }
}
