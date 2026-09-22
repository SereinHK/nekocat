package com.nekochat.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nekochat.R
import com.nekochat.bluetooth.BleTransport
import com.nekochat.bluetooth.ClassicTransport
import com.nekochat.bluetooth.PeerLink
import com.nekochat.bluetooth.RfcommLink
import com.nekochat.bluetooth.TcpLink
import com.nekochat.bluetooth.TransportListener
import com.nekochat.bluetooth.WifiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 已连接的对端。 */
data class Peer(
    val deviceId: String,
    val nickname: String,
    val address: String,
    val transport: TransportType,
    val connectedAt: Long = System.currentTimeMillis()
)

/** 扫描到的可连接设备（同时适用于经典与 BLE）。 */
data class DiscoveredDevice(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val rssi: Int? = null,
    val transport: TransportType
)

/**
 * 组网核心：管理所有链路、握手、消息路由与去重。
 *
 * 任意两节点建立一条链路即可。
 * **群聊**通过「直连 + 逐跳中继」在全网扩散（收到消息的节点会继续转发给除来源外的链路），
 * 因此 A—B—C 链式拓扑下 A 的群聊能到达 C；
 * 收到重复 msgId 直接丢弃以保证幂等。
 *
 * **私聊例外**：带 `targetId` 的消息不参与中继（见 [relayIfNeeded]），
 * 只能发给与自己**直接相连**的节点。三节点链式下 A 无法私聊 C。
 *
 * 所有对外回调都发生在主线程。
 *
 * 两个构造参数是为可测试性预留的：生产环境走默认值（主线程 Handler + 真实蓝牙传输），
 * 单元测试用内存回环链路和一个直调 Handler 跑协议与路由逻辑，无需真机。
 */
class MeshManager(
    private val context: Context,
    private val prefs: ChatPreferences,
    private val main: Handler? = null
) : TransportListener {

    interface Listener {
        fun onPeersChanged(peers: List<Peer>)
        fun onMessage(message: ChatMessage)
        fun onStatus(text: String)
        fun onMessageDelivery(id: String, delivered: Int, expected: Int)

        /** 扫描到附近的 BLE 设备（仅用于展示，连接由用户或自动逻辑触发）。 */
        fun onDeviceFound(device: DiscoveredDevice) {}
    }

    companion object {
        private const val TAG = "MeshManager"
        private const val HELLO_INTERVAL_MS = 12_000L
        private const val MESSAGE_TTL_MS = 5 * 60_000L

        /**
         * 链路接入后等待握手的宽限期。
         *
         * `Attached` 是在 `onLinkUp` 时建立的，而 BLE 客户端此时握手**尚未完成**
         * （`BleClientLink.isConnected` 读的是 `connected && !closed`，
         * 而 `connected` 要等 CCCD 订阅成功才置位）。
         * 在这个窗口内绝不能用 `!isConnected` 判定链路已死，否则会把正在握手的
         * 活链路删掉 —— 真机上表现为链路反复建立又消失、双方都上不了线。
         */
        private const val HANDSHAKE_GRACE_MS = 3_000L

        /**
         * 由 deviceId 派生随广播发出的「拨号标识」。
         *
         * 取前 [BleTransport.ADV_TOKEN_BYTES] 个字母数字字符，双方对这个量有共同认知，
         * 因此比较结果必然反对称 —— 这是 [dialDecision] 能成立的前提。
         */
        internal fun dialTokenOf(deviceId: String): String =
            deviceId.takeWhile { it.isLetterOrDigit() }
                .take(BleTransport.ADV_TOKEN_BYTES)
                .lowercase()

        /**
         * 首次相遇时谁主动拨号 —— **必须是反对称的判断**：
         * 一方判 true 时另一方必须判 false，否则两边都拨（链路互抢）
         * 或两边都不拨（永远连不上）。
         *
         * 真机踩过的坑都源于「拿各自的量去比」：
         *
         * 1. 用 `BluetoothAdapter.getAddress()` 当本机地址 —— 真机只返回占位符
         *    `02:00:00:00:00:00`，两台设备拿到同一个值，与对端真实地址比较时双方都判 false。
         * 2. 用「本机 deviceId 拼对端 MAC」比较 —— 两侧比的**根本不是同一对字符串**
         *    （手机比的是「手机ID vs 平板MAC」，平板比的是「平板ID vs 手机MAC」），
         *    实测两台设备 ID 都以 `2` 开头、两个 MAC 都以 `6` 开头时，
         *    双方字符串比较同时取负 → 双方都不拨号 → 永久僵死（已用真机复现）。
         *
         * 现在只比**同一个量**：广播里带的拨号标识。两侧拿到的是同一对
         * (tokenA, tokenB)，交换视角后比较结果必然相反。
         *
         * @param peerToken 对端广播里的拨号标识；对端版本较旧没有该字段时为 null
         */
        internal fun dialDecision(
            localToken: String,
            peerToken: String?,
            localId: String,
            peerAddress: String
        ): Boolean {
            if (peerToken != null && peerToken != localToken) return localToken > peerToken
            // 兜底：对端没带标识（旧版本），或两个标识极小概率撞车。
            // 这条规则不保证反对称，只在无法取得共同量时使用。
            return (localId + "|" + peerAddress).compareTo(peerAddress + "|" + localId) > 0
        }
    }

    private val handler: Handler = main ?: Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val classic by lazy { ClassicTransport(context) }
    private val ble by lazy { BleTransport(context) }
    private val wifi by lazy { WifiTransport(context) }

    private val adapter: BluetoothAdapter?
        @SuppressLint("MissingPermission")
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    /** 当前活跃链路：key 为设备 MAC，value 为该链路的接入对象。 */
    private val links = ConcurrentHashMap<String, Attached>()

    /** 已完成握手的对端，key 为对端 deviceId。 */
    private val peers = ConcurrentHashMap<String, Peer>()

    /**
     * 地址 → deviceId 的学习表。
     *
     * 拨号前只知道对端 MAC，而「谁该拨号」的判定需要 deviceId，
     * 所以收到过 HELLO 的对端在这里留下映射，供后续重连时判定。
     */
    private val peerIdByAddr = ConcurrentHashMap<String, String>()

    /** 已处理过的消息 ID，用于中继去重（幂等）。 */
    private val seenMessages = ConcurrentHashMap<String, Long>()

    private val outbound = Channel<Envelope>(Channel.UNLIMITED)
    private val running = AtomicBoolean(false)
    private var serverHandle: Closeable? = null
    private var relayRunning = false

    init {
        // 发送队列的消费者常驻，与蓝牙是否启动无关
        startRelayLoop()
    }

    /** 用户是否主动处于「扫描」状态：只有此时才会自动连接新发现的设备。 */
    private val scanningEnabled = AtomicBoolean(false)

    @Volatile
    private var listener: Listener? = null

    /** 本机蓝牙 MAC，用于过滤自己发出的广播。 */
    private val localMac: String
        @SuppressLint("MissingPermission", "HardwareIds")
        get() = runCatching { adapter?.address ?: "" }.getOrDefault("")

    private val helloRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            // 顺手对一次账：死链路和它留下的 peer 必须清掉。
            // 只在事件里清是不够的 —— 没有新事件时（比如链路静默失活后再无变化）
            // 那条死链路会一直占着 address，界面上显示「在线」但一条消息都发不出去。
            // 心跳每 12 秒一次，正好当作兜底的巡检。
            pruneDeadLinks()
            logTopology()
            broadcastHello()
            handler.postDelayed(this, HELLO_INTERVAL_MS)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * 每 12 秒把当前的链路表和在线表打一行日志。
     *
     * **为什么要定期打**：三台设备下出现过「A 看到 2 台、B 和 C 各看到 1 台」这种
     * 星型拓扑，而当时手头没有第三台设备可复现。这类问题只能靠真机日志定位，
     * 但拓扑是慢慢收敛出来的，事后再去翻日志根本拼不出「某一时刻谁连着谁」。
     * 心跳本来就在跑，顺手记一行，下次三台一测就能直接看到拓扑长什么样。
     *
     * 只在有链路时打，避免空转刷屏。
     */
    private fun logTopology() {
        val currentLinks = links.values
        if (currentLinks.isEmpty()) return
        val linkText = currentLinks.joinToString(", ") { attached ->
            val id = attached.deviceId?.take(6) ?: "?"
            "${attached.link.address}->$id/${if (attached.link.isConnected) "活" else "死"}"
        }
        val peerText = peers.values.joinToString(", ") { "${it.deviceId.take(6)}@${it.address}" }
        Log.i(TAG, "拓扑 links=[$linkText] peers=[$peerText]")
    }

    /**
     * 把一条已经可用的链路直接接入组网。
     *
     * 生产路径由 RFCOMM/BLE 传输层回调 [onLinkUp] 触发；单元测试用内存回环链路
     * 走这个方法，从而在不依赖真机、不依赖蓝牙栈的情况下验证握手与路由。
     */
    fun attachLoopbackLink(link: PeerLink) {
        attachToMain(link)
    }

    /** 立即广播一次 HELLO（心跳的同步版本，测试用）。 */
    fun broadcastHelloNow() {
        broadcastHello()
    }

    /** 当前已完成握手的对端（测试与调试用）。 */
    fun currentPeers(): List<Peer> = peers.values.toList()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            when (prefs.transport) {
                TransportType.RFCOMM -> startRfcommServer()
                TransportType.BLE -> startBleServer()
                TransportType.WIFI -> startWifiServer()
            }
            postStatus(context.getString(R.string.status_started, prefs.transport.labelOf(context)))
        } catch (e: SecurityException) {
            postStatus(context.getString(R.string.status_start_failed_no_bt_permission))
        } catch (e: Exception) {
            postStatus(context.getString(R.string.status_start_failed, e.message ?: ""))
        }
        handler.postDelayed(helloRunnable, HELLO_INTERVAL_MS)
    }

    /** WiFi 模式下本机可被连接到的地址（ip:port）；未监听时为 null。 */
    @Volatile
    var wifiHostAddress: String? = null
        private set

    /** 本机局域网地址（只返回展示用的主地址），用于 UI 提示对端该填什么。 */
    fun wifiLocalIps(): List<String> = wifi.displayAddresses()

    fun stop() {
        if (!running.getAndSet(false)) {
            // 已经停止，仍然清理一次资源
        }
        handler.removeCallbacks(helloRunnable)
        runCatching { serverHandle?.close() }
        serverHandle = null
        wifiHostAddress = null
        scanningEnabled.set(false)
        ble.stopScan()
        links.values.forEach { runCatching { it.link.close() } }
        links.clear()
        peers.clear()
        peerIdByAddr.clear()
        postStatus(context.getString(R.string.status_stopped))
        handler.post { postPeers() }
    }

    val isRunning: Boolean
        get() = running.get()

    private fun startRfcommServer() {
        // 切回经典模式时关闭可能仍在运行的 BLE 服务端与广播
        ble.stopServer()
        serverHandle = classic.startServer(
            onSocket = { socket ->
                runCatching {
                    val link = classic.wrap(socket)
                    link.onClosed { linkClosed(link, context.getString(R.string.reason_connection_closed)) }
                    attach(link)
                    // 独立线程读取该 socket
                    Thread({
                        link.readLoop(
                            onFrame = { payload: ByteArray -> deliverRaw(link, payload) },
                            onDown = { reason: String? -> linkClosed(link, reason) }
                        )
                    }, "rfcomm-read-${link.address}").apply {
                        isDaemon = true
                        start()
                    }
                }
            },
            onError = { message -> postStatus(context.getString(R.string.status_rfcomm_listen_ended, message)) }
        )
    }

    private fun startBleServer() {
        serverHandle?.let { runCatching { it.close() } }
        serverHandle = null
        val ok = ble.startServer(this, prefs.nickname, dialTokenOf(prefs.deviceId))
        if (!ok) {
            postStatus(context.getString(R.string.status_ble_server_failed))
        }
        // BLE 模式必须开启自动拨号，否则双方只会各自广播+扫描却互不连接。
        // scanningEnabled 之前只在用户手动点「扫描」时才置位（那是为了不让
        // 设备页误连附近的耳机/音箱），但 BLE 组网依赖它来建立链路。
        // 扫描本身已按服务 UUID 过滤，所以不会连到无关设备。
        scanningEnabled.set(true)
        startBleScanInternal()
    }

    /**
     * WiFi 模式：起 TCP 服务端等待对端连入。
     *
     * 优先用固定端口（对端要靠二维码/手填地址找到我们）。
     * 但**端口被占用不能让整个监听失败** —— 真机上就踩过：
     * 重装后旧进程可能仍持有端口，`bind` 抛 `EADDRINUSE`，
     * 于是 `wifiHostAddress` 一直是 null，界面上连二维码按钮都不显示。
     * 所以这里按「固定端口 → 备用端口 → 系统分配」逐级退让，
     * 端口号会随二维码一起给到对端，无需事先约定。
     */
    private fun startWifiServer() {
        serverHandle?.let { runCatching { it.close() } }
        serverHandle = null

        val candidates = listOf(
            WifiTransport.DEFAULT_PORT,
            WifiTransport.DEFAULT_PORT + 1,
            WifiTransport.DEFAULT_PORT + 2,
            0 // 系统分配：一定能成功
        )
        var lastError: Exception? = null
        // 监听端口要在 socket 回调里用来拼地址，先提到外面
        var listeningPort = 0
        for (port in candidates) {
            val result = runCatching {
                wifi.startServer(
                    port = port,
                    onSocket = { socket ->
                        val link = TcpLink(socket, context)
                        link.onClosed { linkClosed(link, context.getString(R.string.reason_connection_closed)) }
                        attach(link)
                        // 对端连进来了 —— 它的源 IP 与哪个本地地址同网段，
                        // 哪个就是对方真正能走通的地址。这比猜「系统把哪条链路当主链路」
                        // 可靠得多（设备可能开了双 WiFi 加速，同时有多个有效地址）。
                        wifi.addressOnSameSubnetAs(socket.inetAddress?.hostAddress)
                            ?.let { reachable ->
                                val port2 = listeningPort
                                if (port2 > 0) {
                                    val updated = "$reachable:$port2"
                                    if (updated != wifiHostAddress) {
                                        Log.w(TAG, "对端从 $reachable 连入，监听地址更新为 $updated")
                                        wifiHostAddress = updated
                                        postStatus(context.getString(R.string.status_wifi_listening, updated))
                                    }
                                }
                            }
                        Thread({
                            link.readLoop(
                                onFrame = { payload: ByteArray -> deliverRaw(link, payload) },
                                onDown = { reason: String? -> linkClosed(link, reason) }
                            )
                        }, "wifi-read-${link.address}").apply { isDaemon = true }.start()
                    },
                    onError = { message -> postStatus(context.getString(R.string.status_wifi_listen_ended, message)) }
                )
            }
            result.onSuccess { handle ->
                serverHandle = handle
                listeningPort = handle.port
                if (port != WifiTransport.DEFAULT_PORT) {
                    Log.w(TAG, "默认端口 ${WifiTransport.DEFAULT_PORT} 不可用，改用 ${handle.port}")
                }
                // 没有局域网地址时把 wifiHostAddress 留成 null。
                // 旧写法是拼 "未连接 WiFi:端口" —— 那是个假地址，会被界面当成真实地址显示、
                // 甚至编进二维码，对端照着连必然失败（`?:` 在当时其实永不生效，
                // 因为 primaryAddress() 返回的是字符串哨兵值而不是 null）。
                val host = wifi.primaryAddress()
                if (host == null) {
                    wifiHostAddress = null
                    postStatus(context.getString(R.string.status_wifi_no_lan_address, handle.port))
                } else {
                    wifiHostAddress = "$host:${handle.port}"
                    postStatus(context.getString(R.string.status_wifi_listening, wifiHostAddress))
                }
                return
            }
            lastError = result.exceptionOrNull() as? Exception
            Log.w(TAG, "监听端口 $port 失败：${result.exceptionOrNull()?.message}，尝试下一个")
        }

        wifiHostAddress = null
        postStatus(
            context.getString(
                R.string.status_wifi_server_failed,
                lastError?.message ?: context.getString(R.string.status_wifi_no_port)
            )
        )
    }

    /** WiFi 模式：主动连接指定 ip:port。 */
    fun connectWifi(host: String, port: Int) {
        scope.launch {
            try {
                postStatus(context.getString(R.string.status_wifi_connecting, host, port))
                val link = wifi.connect(host, port)
                link.onClosed { linkClosed(link, context.getString(R.string.reason_connection_closed)) }
                attach(link)
                Thread({
                    link.readLoop(
                        onFrame = { payload: ByteArray -> deliverRaw(link, payload) },
                        onDown = { reason: String? -> linkClosed(link, reason) }
                    )
                }, "wifi-read-$host").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                postStatus(context.getString(R.string.status_wifi_connect_failed, e.message ?: ""))
            }
        }
    }

    /**
     * BLE 模式下持续扫描，才能发现正在广播的其他节点。
     *
     * **不加 ScanFilter**。真机验证过：加了按服务 UUID 的过滤器后，
     * 手机端系统层已上报 800+ 条扫描结果，而回调一次都没触发（对端广播包与过滤条件不匹配），
     * 表现为「两台都在广播+扫描却永远发现不了对方」。
     * 正确性改由 [BleTransport] 在应用层校验服务 UUID 保证，代价是多收一些无关广播。
     */
    @SuppressLint("MissingPermission")
    private fun startBleScanInternal(useServiceFilter: Boolean = false) {
        ble.startScan(
            onFound = { device, rssi, name, advToken ->
                if (device.address == localMac) return@startScan
                // 只有「已经和这台设备连着一条活链路」才跳过。
                //
                // 以前判的是 `peers` 里有没有这个地址 —— 而 peers 会在链路死掉后
                // 依旧留着记录（失活不一定经 linkClosed），于是这台设备再也拨不出去：
                // 真机日志里表现为扫描结果一条接一条、却连一句「跳过拨号」都没有
                // （静默 return），界面上还显示「在线」，消息永远发不出去。
                val knownPeer = peers.values.firstOrNull { it.address == device.address }
                if (knownPeer != null &&
                    links.values.any { it.deviceId == knownPeer.deviceId && it.link.isConnected }
                ) {
                    return@startScan
                }
                val discovered = DiscoveredDevice(
                    name = name ?: device.address,
                    address = device.address,
                    bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
                    rssi = rssi,
                    transport = TransportType.BLE
                )
                handler.post { listener?.onDeviceFound(discovered) }
                // 拨号条件逐个记日志，避免「发现了却静默不连」这种难查的情况
                if (links.containsKey(device.address)) {
                    Log.d(TAG, "跳过拨号 ${device.address}：已有链路")
                } else if (!scanningEnabled.get()) {
                    Log.d(TAG, "跳过拨号 ${device.address}：扫描未启用")
                } else if (!shouldDial(device.address, advToken)) {
                    Log.d(TAG, "跳过拨号 ${device.address}：本机标识 ${localDialToken()} 较小（对端 ${advToken ?: "无"}），等对端连我")
                } else if (ble.isPeerConnected(device.address)) {
                    Log.d(TAG, "跳过拨号 ${device.address}：对端已连上本机")
                } else {
                    Log.i(TAG, "开始拨号 ${device.address}（本机标识 ${localDialToken()}，对端 ${advToken ?: "无"}）")
                    ble.connect(device, this) { link ->
                        if (link != null) {
                            link.onClosed { linkClosed(link, context.getString(R.string.reason_peer_closed_ble)) }
                            attachToMain(link)
                        } else {
                            Log.w(TAG, "拨号失败（已重试）${device.address}")
                        }
                    }
                }
            },
            onError = { message -> postStatus(context.getString(R.string.status_ble_scan_failed, message)) },
            useServiceFilter = useServiceFilter
        )
    }

    /**
     * 本机随广播发出的拨号标识（日志与判定共用同一份推导）。
     */
    private fun localDialToken(): String = dialTokenOf(prefs.deviceId)

    /**
     * 单向拨号判定：保证任一设备对**只由一方**主动拨号，避免双主角色互抢。
     *
     * 判定逻辑见 [dialDecision]（纯函数，可单独测试反对称性）。
     * 这里只负责取本机标识、对端标识与对端地址。
     */
    private fun shouldDial(remoteAddress: String, peerToken: String?): Boolean {
        val local = prefs.deviceId
        // 已知对端身份时，用它的 deviceId 推算出它广播的标识，保持与首次相遇同一套规则
        val token = peerToken ?: peerIdByAddr[remoteAddress]?.let { dialTokenOf(it) }
        return dialDecision(dialTokenOf(local), token, local, remoteAddress)
    }

    // ------------------------------------------------------------------
    // 主动连接
    // ------------------------------------------------------------------

    /** 经典蓝牙：连接指定 MAC（通常来自已配对列表或扫描结果）。 */
    @SuppressLint("MissingPermission")
    fun connectRfcomm(address: String) {
        scope.launch {
            try {
                val device = adapter?.getRemoteDevice(address) ?: run {
                    postStatus(context.getString(R.string.status_unknown_device, address))
                    return@launch
                }
                postStatus(context.getString(R.string.status_connecting_address, address))
                val link = classic.connect(device)
                link.onClosed { linkClosed(link, context.getString(R.string.reason_connection_closed)) }
                // 主动连接时由本线程负责读取
                attach(link)
                Thread({
                    link.readLoop(
                        onFrame = { payload: ByteArray -> deliverRaw(link, payload) },
                        onDown = { reason: String? -> linkClosed(link, reason) }
                    )
                }, "rfcomm-read-$address").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                postStatus(context.getString(R.string.status_connect_failed, e.message ?: ""))
            }
        }
    }

    /** 已配对设备列表。 */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<DiscoveredDevice> = classic.bondedDevices().map {
        DiscoveredDevice(
            name = runCatching { it.name }.getOrNull() ?: it.address,
            address = it.address,
            bonded = true,
            transport = TransportType.RFCOMM
        )
    }

    /** 手动停止扫描（同时关闭自动连接）。 */
    fun stopScan() {
        scanningEnabled.set(false)
        ble.stopScan()
    }

    /** 手动开始扫描：发现广播本服务 UUID 的设备时会自动尝试连接。 */
    fun startScan() {
        scanningEnabled.set(true)
        startBleScanInternal()
    }

    // ------------------------------------------------------------------
    // 链路管理
    // ------------------------------------------------------------------

    private class Attached(
        val link: PeerLink,
        @Volatile var deviceId: String? = null,
        @Volatile var nickname: String? = null,
        @Volatile var handshakeSent: Boolean = false,
        /** 接入时刻。用于区分「还在握手」与「确实死了」，见 [pruneDeadLinks]。 */
        val attachedAt: Long = System.currentTimeMillis()
    )

    private fun attach(link: PeerLink) = attachToMain(link)

    /**
     * 清掉已断开、但没走 [linkClosed] 的残留链路，并同步摘掉对应的 peer。
     *
     * **为什么需要**：`BleClientLink` 的失活不一定经 `onLinkDown` 通知到组网层
     * （`handleWriteFailure()` 只把 `connected` 置 false 就返回了）。
     * 于是 `links` 里会留下 `isConnected=false` 的死条目，而它同时导致两个后果：
     * 1. 拨号被判「已有链路」永久跳过 —— 手机明明能扫到对端，却再也不发起连接；
     * 2. 界面靠 `peers` 显示「在线」，而实际一条活链路都没有，消息静默发不出去。
     *
     * **判据必须带时间宽限**：`Attached` 在 `onLinkUp` 时就建立，而 BLE 客户端
     * 那一刻握手还没完成。若直接按 `!isConnected` 删，会把正在握手的活链路一起删掉，
     * 表现为链路反复抖动、双方都上不了线（真机实测过这个反效果）。
     */
    private fun pruneDeadLinks() {
        val now = System.currentTimeMillis()
        val dead = links.values.filter {
            !it.link.isConnected && now - it.attachedAt > HANDSHAKE_GRACE_MS
        }
        dead.forEach { attached ->
            links.remove(attached.link.address, attached)
            Log.w(TAG, "清理失效链路 ${attached.link.address}（deviceId=${attached.deviceId}）")
        }
        // 摘掉已经没有活链路指向的 peer。
        // 链路可能在别处被移除（例如拨号重连时换掉旧链路），只在 linkClosed 里删 peer
        // 会漏掉这些路径，留下一具「显示在线、实际没链路」的空壳。
        val orphans = peers.values.filter { peer ->
            links.values.none { it.deviceId == peer.deviceId && it.link.isConnected }
        }
        orphans.forEach {
            peers.remove(it.deviceId)
            Log.w(TAG, "清理无链路的在线记录 ${it.deviceId}（${it.nickname}）")
        }
    }

    private fun attachToMain(link: PeerLink) {
        val existing = links[link.address]
        if (existing != null && existing.link.isConnected && existing.link !== link) {
            // 同一台设备已有存活链路（例如 BLE 双向互连时的另一条）：保留旧链路
            runCatching { link.close() }
            return
        }
        if (existing != null && existing.link !== link) {
            // 旧链路已失效但尚未回调断开，先移除避免占用 address 槽位
            links.remove(link.address, existing)
        }
        val attached = Attached(link)
        links[link.address] = attached
        handler.post {
            attached.handshakeSent = true
            // HELLO 必须在 IO 线程发。
            //
            // 踩过的坑：这里原先直接在 handler（主线程）里 write，
            // BluetoothSocket 不受限所以一直没暴露，换成 TCP 后立刻抛
            // NetworkOnMainThreadException —— 且被 runCatching 静默吞掉，
            // 表现为「链路已建立但两边永远互不在线」。
            scope.launch {
                val result = runCatching { link.write(Wire.encode(helloEnvelope()), { }) }
                if (result.isFailure) {
                    Log.w(TAG, "发送 HELLO 失败（${link.address}）：${result.exceptionOrNull()}")
                    postStatus(
                        context.getString(
                            R.string.status_hello_failed,
                            result.exceptionOrNull()?.message
                                ?: result.exceptionOrNull()?.javaClass?.simpleName
                                ?: ""
                        )
                    )
                }
            }
            postStatus(
                context.getString(
                    R.string.status_link_established,
                    link.displayName,
                    link.transport.labelOf(context)
                )
            )
        }
    }

    private fun linkClosed(link: PeerLink, reason: String?) {
        val attached = links.remove(link.address) ?: return
        val deviceId = attached.deviceId
        if (deviceId != null) {
            peers.remove(deviceId)
        }
        handler.post {
            postPeers()
            reason?.let {
                postStatus(
                    context.getString(
                        R.string.status_link_disconnected,
                        attached.nickname ?: link.displayName,
                        it
                    )
                )
            }
        }
    }

    private fun helloEnvelope(): Envelope = Envelope(
        type = MsgType.HELLO,
        sender = DeviceInfo(prefs.deviceId, prefs.nickname)
    )

    /**
     * 向所有链路广播 HELLO。
     *
     * 必须在 IO 线程执行：TCP 的 socket 写会在主线程抛 [android.os.NetworkOnMainThreadException]。
     * 这个方法由主线程的 [helloRunnable] 调用，所以内部自己切线程。
     */
    private fun broadcastHello() {
        val frame = Wire.encode(helloEnvelope())
        val targets = links.values.toList()
        scope.launch {
            targets.forEach { attached ->
                runCatching { attached.link.write(frame, { }) }
                    .onFailure { Log.w(TAG, "广播 HELLO 失败（${attached.link.address}）：${it}") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 数据入口
    // ------------------------------------------------------------------

    /**
     * 数据入口。**对入参做容错**：
     *
     * 传输层的契约是「传去掉 4 字节长度头的 JSON 负载」，但实测各条链路并不一致
     * （BLE 服务端侧曾把整帧原样交上来，导致 JSON 解析失败）。
     * 与其让每个传输实现各自保证，不如在这里统一识别：
     * 若开头 4 字节恰好等于剩余长度，就按带前缀的整帧处理。
     */
    private fun deliverRaw(link: PeerLink, payload: ByteArray) {
        val body = stripLengthPrefixIfPresent(payload)
        val envelope = Envelope.decode(body)
        if (envelope == null) {
            Log.w(
                TAG,
                "无法解析来自 ${link.address} 的 ${payload.size} 字节，前 8 字节=" +
                    payload.take(8).joinToString(" ") { "%02x".format(it) }
            )
            postStatus(context.getString(R.string.status_unparsable_packet))
            return
        }
        handleEnvelope(link, envelope)
    }

    /** 若 [data] 是「4 字节长度头 + 负载」的整帧，返回负载；否则原样返回。 */
    private fun stripLengthPrefixIfPresent(data: ByteArray): ByteArray {
        if (data.size < 5) return data
        val declared = ((data[0].toInt() and 0xFF) shl 24) or
            ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or
            (data[3].toInt() and 0xFF)
        return if (declared == data.size - 4) data.copyOfRange(4, data.size) else data
    }

    private fun handleEnvelope(link: PeerLink, envelope: Envelope) {
        // 对端身份以信封中的 sender 为准（避免依赖 MAC，兼容随机化地址）
        val attached = links[link.address]
        if (attached == null) {
            // 这条日志很关键：地址对不上时会静默丢弃所有数据，表现为「链路已建立但永远不在线」
            Log.w(
                TAG,
                "丢弃 ${envelope.type}：链路地址 ${link.address} 不在表中，当前表=${links.keys}"
            )
            return
        }

        when (envelope.type) {
            MsgType.HELLO, MsgType.PING, MsgType.PONG -> {
                // 学到 deviceId 之后先查重。
                //
                // BLE 的地址是会轮换的（RPA）：对端换个地址广播，本机就会把它当成
                // 新设备再连一次，于是同一台设备留下两条链路 —— 真机日志里能看到
                // 两个地址交替收发，同一条消息被发两遍。
                // 链路是按地址索引的，只有 deviceId 才知道「这是同一个设备」。
                val duplicate = links.values.firstOrNull {
                    it !== attached && it.deviceId == envelope.sender.deviceId
                }
                if (duplicate != null) {
                    Log.i(
                        TAG,
                        "已有指向 ${envelope.sender.deviceId} 的链路（${duplicate.link.address}），" +
                            "关闭重复的 ${link.address}"
                    )
                    links.remove(link.address, attached)
                    peerIdByAddr.remove(link.address)
                    runCatching { link.close() }
                    return
                }
                val isNew = peers[envelope.sender.deviceId] == null
                attached.deviceId = envelope.sender.deviceId
                attached.nickname = envelope.sender.nickname
                // 记住地址与 deviceId 的对应关系，供下次判定谁拨号
                peerIdByAddr[link.address] = envelope.sender.deviceId
                peers[envelope.sender.deviceId] = Peer(
                    deviceId = envelope.sender.deviceId,
                    nickname = envelope.sender.nickname,
                    address = link.address,
                    transport = link.transport
                )
                if (envelope.type == MsgType.PING) {
                    // 立即回 PONG，保持链路活跃。
                    // 走 scope（IO）而不是当前线程：onFrame 的线程由各传输实现决定，
                    // TCP 虽然在自己的读线程上回调，但这里统一不假设调用线程。
                    val pong = Envelope(MsgType.PONG, DeviceInfo(prefs.deviceId, prefs.nickname))
                    val frame = Wire.encode(pong)
                    scope.launch { runCatching { link.write(frame, { }) } }
                }
                if (isNew) {
                    handler.post {
                        postPeers()
                        postStatus(context.getString(R.string.status_peer_joined, envelope.sender.nickname))
                    }
                }
            }

            MsgType.CHAT -> handleChat(link, attached, envelope)
        }
    }

    private fun handleChat(link: PeerLink, attached: Attached, envelope: Envelope) {
        val messageId = envelope.msgId ?: UUID.randomUUID().toString()
        val target = envelope.targetId

        // 私聊过滤：目标不是本机且本机不负责中继则忽略
        if (target != null && target != prefs.deviceId) {
            relayIfNeeded(envelope, link)
            return
        }

        // 私聊：目标是自己，则不再转发
        val firstTime = markSeen(messageId)
        if (!firstTime) return

        val chat = ChatMessage(
            id = messageId,
            senderId = envelope.sender.deviceId,
            senderName = envelope.sender.nickname,
            text = envelope.text.orEmpty(),
            timestamp = envelope.timestamp,
            outgoing = false,
            targetId = target
        )
        handler.post { listener?.onMessage(chat) }

        if (target == null) relayIfNeeded(envelope, link)
    }

    /**
     * 中继一则群聊消息：转发给除来源之外的其它链路，实现逐跳扩散。
     *
     * **私聊不中继**（`targetId != null` 直接返回）：私聊只能到达与自己直连的节点。
     * 这是一条有意的限制 —— 放开它需要处理多跳去重与环路，而 `markSeen` 目前只在
     * 消息**到达本机**时调用，不足以支撑中继去重。要放开的话这两处得一起改。
     */
    private fun relayIfNeeded(envelope: Envelope, from: PeerLink) {
        if (envelope.targetId != null) return
        val frame = Wire.encode(envelope)
        links.values.forEach { attached ->
            if (attached.link.address != from.address && attached.link.isConnected) {
                runCatching { attached.link.write(frame, { }) }
            }
        }
    }

    /** @return true 表示首次见到该消息。 */
    private fun markSeen(messageId: String): Boolean {
        val now = System.currentTimeMillis()
        if (seenMessages.size > 512) {
            seenMessages.entries.removeIf { now - it.value > MESSAGE_TTL_MS }
        }
        return seenMessages.putIfAbsent(messageId, now) == null
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** 广播一条文本消息，返回本地消息对象。 */
    fun sendBroadcast(text: String): ChatMessage {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = prefs.deviceId,
            senderName = prefs.nickname,
            text = text,
            timestamp = System.currentTimeMillis(),
            outgoing = true
        )
        markSeen(message.id)
        val envelope = Envelope(
            type = MsgType.CHAT,
            sender = DeviceInfo(prefs.deviceId, prefs.nickname),
            text = text,
            msgId = message.id
        )
        enqueue(envelope, message.id)
        return message
    }

    /** 私聊：只发送给目标设备。 */
    fun sendPrivate(targetId: String, text: String): ChatMessage {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = prefs.deviceId,
            senderName = prefs.nickname,
            text = text,
            timestamp = System.currentTimeMillis(),
            outgoing = true,
            targetId = targetId
        )
        markSeen(message.id)
        val envelope = Envelope(
            type = MsgType.CHAT,
            sender = DeviceInfo(prefs.deviceId, prefs.nickname),
            text = text,
            msgId = message.id,
            targetId = targetId
        )
        enqueue(envelope, message.id)
        return message
    }

    /** 把待发送消息投入发送队列，由单消费者协程顺序写出。 */
    private fun enqueue(envelope: Envelope, messageId: String) {
        val result = outbound.trySend(envelope)
        if (result.isFailure) {
            handler.post { listener?.onMessageDelivery(messageId, 0, links.size) }
        }
    }

    /**
     * 启动发送队列的消费协程。
     *
     * 在 init 里就启动，而不是等到 [start]：它只消费内存队列、不接触蓝牙，
     * 早启动可以避免「消息已入队但无消费者」的窗口。
     */
    private fun startRelayLoop() {
        if (relayRunning) return
        relayRunning = true
        scope.launch {
            for (envelope in outbound) {
                val targets = if (envelope.targetId == null) {
                    links.values.toList()
                } else {
                    links.values.filter { it.deviceId == envelope.targetId }
                }
                if (targets.isEmpty()) {
                    envelope.msgId?.let { id ->
                        handler.post { listener?.onMessageDelivery(id, 0, 0) }
                    }
                    continue
                }
                val frame = Wire.encode(envelope)
                val total = targets.size
                // 写入是**异步**的（BLE 要等 GATT 回调、TCP 在自己的线程上写），
                // 所以不能在这里直接读计数 —— 以前就是这么写的，结果长消息必然显示
                // 「已发送 ×0」：写完 4 个分片要一百多毫秒，而这里立刻就上报了。
                // 改成先报 0/total 占位，每收到一个成功回调就更新一次。
                val delivered = AtomicInteger(0)
                envelope.msgId?.let { id ->
                    handler.post { listener?.onMessageDelivery(id, 0, total) }
                }
                targets.forEach { attached ->
                    if (!attached.link.isConnected) return@forEach
                    runCatching {
                        attached.link.write(frame) { ok ->
                            if (ok) {
                                val done = delivered.incrementAndGet()
                                envelope.msgId?.let { id ->
                                    handler.post { listener?.onMessageDelivery(id, done, total) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 回调派发
    // ------------------------------------------------------------------

    override fun onLinkUp(link: PeerLink) {
        attachToMain(link)
    }

    override fun onFrame(link: PeerLink, frame: ByteArray) {
        deliverRaw(link, frame)
    }

    override fun onLinkDown(link: PeerLink, reason: String?) {
        linkClosed(link, reason)
    }

    private fun postPeers() {
        // 每次上报在线列表前先对账：**界面显示的在线设备，必须都有活链路**。
        // 否则会出现「显示在线 → 拨号被跳过（因为 links 里有残留）→ 消息发不出去」
        // 这种自锁状态，而且全程没有任何错误提示（真机复现过）。
        pruneDeadLinks()
        listener?.onPeersChanged(peers.values.sortedBy { it.connectedAt })
    }

    private fun postStatus(text: String) {
        Log.d(TAG, text)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener?.onStatus(text)
        } else {
            handler.post { listener?.onStatus(text) }
        }
    }

    fun shutdown() {
        stop()
        ble.shutdown()
    }
}
