package com.nekochat.chat

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekochat.NekoChatApp
import com.nekochat.R
import com.nekochat.service.ChatForegroundService
import com.nekochat.util.BtPermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 底部导航页面。 */
enum class Tab { CHAT, DEVICES, SETTINGS }

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val peers: List<Peer> = emptyList(),
    val discovered: List<DiscoveredDevice> = emptyList(),
    val paired: List<DiscoveredDevice> = emptyList(),
    val tab: Tab = Tab.CHAT,
    val running: Boolean = false,
    val status: String = "",
    val transport: TransportType = TransportType.RFCOMM,
    val nickname: String = "",
    val scanning: Boolean = false,
    val privateTarget: String? = null,
    val bluetoothEnabled: Boolean = false,
    val bluetoothSupported: Boolean = true,
    val permissionGranted: Boolean = false,
    /**
     * 系统定位服务是否开启。
     *
     * Android 11 及以下 BLE 扫描依赖它；权限给了但定位关着时扫描会**静默返回空**，
     * 所以要把这个状态显示出来，否则用户完全无从判断"为什么扫不到"。
     * API 31+ 恒为 true。
     */
    val locationServiceEnabled: Boolean = true,
    val autoStart: Boolean = true,
    val notifyOnMessage: Boolean = true,
    /** 本机局域网 IPv4 地址（WiFi 模式展示用）。 */
    val wifiLocalIps: List<String> = emptyList(),
    /** WiFi 模式监听中时，本机可被连接到的 ip:port。 */
    val wifiHostAddress: String? = null
) {
    val privatePeerName: String?
        get() = peers.firstOrNull { it.deviceId == privateTarget }?.nickname

    val connectedCount: Int get() = peers.size
}

/**
 * 应用状态中枢：把 [MeshManager] 的回调转换为 Compose 可观察的状态。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: ChatPreferences = (app as NekoChatApp).prefs
    private val mesh = MeshManager(app, prefs)

    /** 资源读取入口，避免每个调用点都写一遍 getApplication。 */
    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private val _state = MutableStateFlow(
        UiState(
            transport = prefs.transport,
            nickname = prefs.nickname,
            status = str(R.string.status_default),
            autoStart = prefs.autoStart,
            notifyOnMessage = prefs.notifyOnMessage
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        mesh.setListener(object : MeshManager.Listener {
            override fun onPeersChanged(peers: List<Peer>) {
                _state.update { current ->
                    // 私聊目标离线时自动回退到群聊
                    val target = current.privateTarget?.takeIf { id -> peers.any { it.deviceId == id } }
                    current.copy(peers = peers, privateTarget = target)
                }
            }

            override fun onMessage(message: ChatMessage) {
                _state.update { it.copy(messages = it.messages + message) }
            }

            override fun onStatus(text: String) {
                _state.update { it.copy(status = text) }
            }

            override fun onMessageDelivery(id: String, delivered: Int, expected: Int) {
                _state.update { current ->
                    current.copy(
                        messages = current.messages.map { message ->
                            if (message.id == id) {
                                message.copy(
                                    deliveredCount = message.deliveredCount + delivered,
                                    expectedCount = maxOf(message.expectedCount, expected)
                                )
                            } else {
                                message
                            }
                        }
                    )
                }
            }

            override fun onDeviceFound(device: DiscoveredDevice) {
                onBleDeviceFound(device)
            }
        })
    }

    /** 权限被永久拒绝时的提示。 */
    fun reportPermissionDenied() {
        _state.update { it.copy(status = str(R.string.status_bt_permission_denied)) }
    }

    /** 刷新权限/蓝牙开关状态；权限齐备且开启自动启动时拉起服务。 */
    fun refreshEnvironment(startIfReady: Boolean = false) {
        val context = getApplication<Application>()
        val supported = BtPermissions.isBluetoothSupported(context)
        val granted = BtPermissions.allGranted(context)
        val enabled = BtPermissions.isBluetoothEnabled(context)
        val locationOn = BtPermissions.isLocationServiceEnabled(context)
        _state.update {
            it.copy(
                bluetoothSupported = supported,
                permissionGranted = granted,
                bluetoothEnabled = enabled,
                locationServiceEnabled = locationOn
            )
        }
        // WiFi 模式走 TCP，不该因为「没开蓝牙 / 没给蓝牙权限」而不自动组网。
        // 之前这里无条件要求 granted && enabled，导致 WiFi 模式下启动时不会自动监听。
        val ready = if (_state.value.transport == TransportType.WIFI) {
            true
        } else {
            granted && enabled
        }
        if (startIfReady && ready && prefs.autoStart && !mesh.isRunning) {
            start()
        }
        // 无论走哪条路径都同步一次局域网状态：应用启动时自动组网的那条路径
        // 之前没有同步，导致「明明在监听，界面却显示未监听、也不给二维码按钮」。
        syncWifiState()
    }

    /** 把 MeshManager 的局域网状态同步到 UI 状态。所有会改变监听状态的路径都要调用。 */
    private fun syncWifiState() {
        val host = mesh.wifiHostAddress
        val ips = mesh.wifiLocalIps()
        _state.update { it.copy(wifiHostAddress = host, wifiLocalIps = ips) }
    }

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.DEVICES) {
            viewModelScope.launch { refreshPaired() }
        }
    }

    fun start() {
        refreshEnvironment()
        // 蓝牙的权限与开关检查只对蓝牙传输有意义。
        // WiFi 局域网走 TCP，不该被「没开蓝牙 / 没给蓝牙权限」挡住 —— 
        // 否则用户选了 WiFi 却启动不了，会很费解。
        val needsBluetooth = _state.value.transport != TransportType.WIFI
        if (needsBluetooth) {
            if (!_state.value.permissionGranted) {
                _state.update { it.copy(status = str(R.string.status_need_bt_permission)) }
                return
            }
            if (!BtPermissions.isBluetoothEnabled(getApplication())) {
                _state.update { it.copy(status = str(R.string.status_need_bt_enabled)) }
                return
            }
        }
        mesh.start()
        _state.update { it.copy(running = true) }
        syncWifiState()
        startBackgroundService()
    }

    fun stop() {
        mesh.stop()
        _state.update { it.copy(running = false, peers = emptyList()) }
        syncWifiState()
        ChatForegroundService.stop(getApplication())
    }

    /** 需要后台保活时拉起前台服务。 */
    private fun startBackgroundService() {
        if (!prefs.notifyOnMessage) return
        ChatForegroundService.start(
            getApplication(),
            "${_state.value.transport.labelOf(getApplication())} · ${_state.value.nickname}"
        )
    }

    fun toggleRunning() {
        if (_state.value.running) stop() else start()
    }

    /** 切换传输方式（会重启组网）。 */
    fun setTransport(transport: TransportType) {
        if (transport == _state.value.transport) return
        val wasRunning = _state.value.running
        if (wasRunning) stop()
        prefs.transport = transport
        _state.update { it.copy(transport = transport, discovered = emptyList()) }
        if (wasRunning) start()
    }

    fun setNickname(nickname: String) {
        val trimmed = nickname.trim().ifEmpty { prefs.nickname }
        prefs.nickname = trimmed
        _state.update { it.copy(nickname = prefs.nickname) }
        // 昵称变更后重新广播身份
        if (mesh.isRunning) {
            mesh.stop()
            mesh.start()
        }
    }

    fun setAutoStart(value: Boolean) {
        prefs.autoStart = value
        _state.update { it.copy(autoStart = value) }
    }

    fun setNotifyOnMessage(value: Boolean) {
        prefs.notifyOnMessage = value
        _state.update { it.copy(notifyOnMessage = value) }
        if (value && _state.value.running) {
            startBackgroundService()
        } else if (!value) {
            ChatForegroundService.stop(getApplication())
        }
    }

    /** 输入框发送：根据是否选中私聊目标决定单播或广播。 */
    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (!_state.value.running) {
            _state.update { it.copy(status = str(R.string.status_need_start_first)) }
            return
        }
        val target = _state.value.privateTarget
        val message = if (target == null) {
            mesh.sendBroadcast(content)
        } else {
            mesh.sendPrivate(target, content)
        }
        _state.update {
            it.copy(
                messages = it.messages + message,
                status = if (target == null) {
                    str(R.string.status_sent_broadcast)
                } else {
                    str(
                        R.string.status_sent_to,
                        it.privatePeerName ?: str(R.string.status_peer_placeholder)
                    )
                }
            )
        }
    }

    fun setPrivateTarget(deviceId: String?) {
        _state.update { it.copy(privateTarget = deviceId) }
    }

    fun refreshPaired() {
        viewModelScope.launch {
            val paired = runCatching { mesh.pairedDevices() }.getOrDefault(emptyList())
            _state.update { it.copy(paired = paired) }
        }
    }

    fun startScan() {
        _state.update { it.copy(scanning = true, discovered = emptyList(), status = str(R.string.status_scanning)) }
        mesh.startScan()
        // BLE 扫描只在 BLE 模式下有效；经典模式下列表来自已配对设备
        viewModelScope.launch {
            refreshPaired()
        }
    }

    fun stopScan() {
        mesh.stopScan()
        _state.update { it.copy(scanning = false, status = str(R.string.status_scan_stopped)) }
    }

    /** 收到的 BLE 广播设备由 MeshManager 直接连接，这里只做展示性更新。 */
    fun onBleDeviceFound(device: DiscoveredDevice) {
        _state.update { current ->
            if (current.discovered.any { it.address == device.address }) {
                current
            } else {
                current.copy(discovered = current.discovered + device)
            }
        }
    }

    fun connect(address: String) {
        _state.update { it.copy(status = str(R.string.status_connecting_address, address)) }
        mesh.connectRfcomm(address)
    }

    // ------------------------------------------------------------------
    // WiFi 局域网
    // ------------------------------------------------------------------

    /** 刷新本机局域网地址，供 UI 提示对端填写。 */
    fun refreshWifiInfo() {
        syncWifiState()
    }

    /**
     * 生成连接码。
     *
     * **只生成一个**：`LinkProperties` 在部分 ROM 上会同时报出主地址和一个已失效的
     * 残留地址（MatePad 上实测是 `192.168.31.5` 与 `192.168.31.45`），
     * 但用户没有任何依据判断哪个能通，列多个只会让人猜。取系统认定的主地址。
     */
    fun wifiEndpoint(): com.nekochat.util.QrPayload.Endpoint? {
        val port = mesh.wifiHostAddress?.substringAfterLast(':', "")?.toIntOrNull() ?: return null
        val host = mesh.wifiLocalIps().firstOrNull() ?: return null
        return com.nekochat.util.QrPayload.Endpoint(
            host = host,
            port = port,
            deviceId = prefs.deviceId,
            nickname = prefs.nickname
        )
    }

    /**
     * 连接指定局域网地址。
     *
     * 允许只填 IP（自动补默认端口）—— 让对端念地址时不用报端口号。
     */
    fun connectWifi(input: String) {
        val text = input.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(status = str(R.string.status_address_required)) }
            return
        }
        val host: String
        val port: Int
        if (text.contains(':')) {
            val parts = text.split(':')
            host = parts[0].trim()
            port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: com.nekochat.bluetooth.WifiTransport.DEFAULT_PORT
        } else {
            host = text
            port = com.nekochat.bluetooth.WifiTransport.DEFAULT_PORT
        }
        if (host.isEmpty()) {
            _state.update { it.copy(status = str(R.string.status_address_invalid)) }
            return
        }
        _state.update { it.copy(status = str(R.string.status_wifi_connecting, host, port)) }
        mesh.connectWifi(host, port)
    }

    /** WiFi 模式下即使未启动组网，也允许直接连接对端。 */
    fun clearMessages() {
        _state.update { it.copy(messages = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        mesh.shutdown()
    }
}
