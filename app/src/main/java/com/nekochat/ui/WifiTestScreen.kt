package com.nekochat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nekochat.R
import com.nekochat.bluetooth.TcpLink
import com.nekochat.bluetooth.WifiTransport
import com.nekochat.chat.Envelope
import com.nekochat.chat.MsgType
import com.nekochat.chat.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * WiFi 局域网传输的自测页。
 *
 * 设计上刻意**不接 MeshManager**：这一版只验证传输层本身（TCP 建链 + 帧协议收发），
 * 把传输问题和组网问题分开，出问题时好定位。
 *
 * 单机也能验证：服务端绑 `0.0.0.0:0`（端口由系统分配），客户端连 `127.0.0.1:<port>`。
 */
@Composable
fun WifiTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val transport = remember { WifiTransport(context) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var hostPort by remember { mutableStateOf<Int?>(null) }
    var serverLink by remember { mutableStateOf<TcpLink?>(null) }
    var clientLink by remember { mutableStateOf<TcpLink?>(null) }

    var targetHost by remember { mutableStateOf("127.0.0.1") }
    var targetPort by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }
    val localIps = remember { transport.localAddresses() }

    // 下面这些文案会出现在非 @Composable 的 lambda（点击回调、socket 回调、协程）里，
    // 必须在 composable 作用域提前取出，回调里再用 String.format 填占位符。
    val startListenText = stringResource(R.string.wifi_test_start_listen)
    val stopListenText = stringResource(R.string.wifi_test_stop_listen)
    val hostStoppedText = stringResource(R.string.wifi_test_host_stopped)
    val hostIncomingText = stringResource(R.string.wifi_test_host_incoming)
    val hostDisconnectedText = stringResource(R.string.wifi_test_host_disconnected)
    val hostRecvText = stringResource(R.string.wifi_test_host_recv)
    val hostReadEndText = stringResource(R.string.wifi_test_host_read_end)
    val hostListenErrorText = stringResource(R.string.wifi_test_host_listen_error)
    val hostListeningText = stringResource(R.string.wifi_test_host_listening)
    val hostStartFailedText = stringResource(R.string.wifi_test_host_start_failed)
    val portText = stringResource(R.string.wifi_test_port)
    val notStartedText = stringResource(R.string.wifi_test_not_started)
    val loopbackFilledText = stringResource(R.string.wifi_test_loopback_filled)
    val clientInvalidPortText = stringResource(R.string.wifi_test_client_invalid_port)
    val clientConnectingText = stringResource(R.string.wifi_test_client_connecting)
    val clientConnectedText = stringResource(R.string.wifi_test_client_connected)
    val clientDisconnectedText = stringResource(R.string.wifi_test_client_disconnected)
    val clientRecvText = stringResource(R.string.wifi_test_client_recv)
    val clientReadEndText = stringResource(R.string.wifi_test_client_read_end)
    val clientConnectFailedText = stringResource(R.string.wifi_test_client_connect_failed)
    val connectedText = stringResource(R.string.wifi_test_connected)
    val disconnectedText = stringResource(R.string.wifi_test_disconnected)
    val localDeviceName = stringResource(R.string.wifi_test_device_name)
    val sentText = stringResource(R.string.wifi_test_sent)

    fun append(text: String) {
        log.add(text)
    }

    Scaffold(
        topBar = {
            PageHeader(
                title = stringResource(R.string.wifi_test_title),
                subtitle = stringResource(R.string.wifi_test_subtitle),
                actions = {
                    TextButton(
                        text = stringResource(R.string.wifi_test_back),
                        onClick = {
                            transport.stopServer()
                            serverLink?.close()
                            clientLink?.close()
                            onBack()
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            // ---------- 本机地址 ----------
            SectionHeader(stringResource(R.string.wifi_test_local_address))
            GroupCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (localIps.isEmpty()) {
                            stringResource(R.string.wifi_test_no_local_ip)
                        } else {
                            localIps.joinToString("\n")
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    VGap(6)
                    Text(
                        text = stringResource(R.string.wifi_test_peer_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            // ---------- 主机 ----------
            SectionHeader(stringResource(R.string.wifi_test_as_host))
            GroupCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (hostPort != null) {
                                    transport.stopServer()
                                    serverLink?.close()
                                    serverLink = null
                                    hostPort = null
                                    append(hostStoppedText)
                                } else {
                                    runCatching {
                                        val handle = transport.startServer(
                                            port = 0,
                                            onSocket = { socket ->
                                                val link = TcpLink(socket, context)
                                                serverLink = link
                                                append(hostIncomingText.format(link.address))
                                                link.onClosed { append(hostDisconnectedText) }
                                                Thread({
                                                    link.readLoop(
                                                        onFrame = { payload ->
                                                            val env = Envelope.decode(payload)
                                                            append(hostRecvText.format(env?.text ?: "?"))
                                                        },
                                                        onDown = { reason ->
                                                            if (reason != null) append(hostReadEndText.format(reason))
                                                        }
                                                    )
                                                }, "wifi-test-accept").apply { isDaemon = true }.start()
                                            },
                                            onError = { append(hostListenErrorText.format(it)) }
                                        )
                                        hostPort = handle.port
                                        append(hostListeningText.format(handle.port))
                                    }.onFailure { append(hostStartFailedText.format(it.message)) }
                                }
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            cornerRadius = 16.dp,
                            minHeight = 38.dp,
                            insideMargin = PaddingValues(horizontal = 18.dp, vertical = 9.dp)
                        ) {
                            Text(if (hostPort == null) startListenText else stopListenText)
                        }
                        HGap(12)
                        Text(
                            text = hostPort?.let { portText.format(it) } ?: notStartedText,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    if (hostPort != null) {
                        VGap(8)
                        TextButton(
                            text = stringResource(R.string.wifi_test_fill_loopback),
                            onClick = {
                                targetHost = "127.0.0.1"
                                targetPort = hostPort.toString()
                                append(loopbackFilledText.format(hostPort))
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }

            // ---------- 客户端 ----------
            SectionHeader(stringResource(R.string.wifi_test_as_client))
            GroupCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = targetHost,
                        onValueChange = { targetHost = it },
                        label = stringResource(R.string.wifi_test_target_ip),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        cornerRadius = 14.dp,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                    VGap(8)
                    TextField(
                        value = targetPort,
                        onValueChange = { targetPort = it.filter { c -> c.isDigit() } },
                        label = stringResource(R.string.wifi_test_port_label),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        cornerRadius = 14.dp,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    VGap(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val port = targetPort.toIntOrNull()
                                if (port == null) {
                                    append(clientInvalidPortText)
                                    return@Button
                                }
                                // 先收起键盘，避免软键盘挡住下面的日志
                                keyboard?.hide()
                                scope.launch {
                                    append(clientConnectingText.format(targetHost, port))
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { transport.connect(targetHost, port) }
                                    }
                                    result.onSuccess { link ->
                                        clientLink = link
                                        append(clientConnectedText.format(link.address))
                                        link.onClosed { append(clientDisconnectedText) }
                                        Thread({
                                            link.readLoop(
                                                onFrame = { payload ->
                                                    val env = Envelope.decode(payload)
                                                    append(clientRecvText.format(env?.text ?: "?"))
                                                },
                                                onDown = { reason ->
                                                    if (reason != null) append(clientReadEndText.format(reason))
                                                }
                                            )
                                        }, "wifi-test-connect").apply { isDaemon = true }.start()
                                    }.onFailure {
                                        append(clientConnectFailedText.format(it.message))
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            cornerRadius = 16.dp,
                            minHeight = 38.dp,
                            insideMargin = PaddingValues(horizontal = 18.dp, vertical = 9.dp)
                        ) {
                            Text(stringResource(R.string.wifi_test_connect))
                        }
                        HGap(12)
                        Text(
                            text = clientLink?.let { connectedText } ?: disconnectedText,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            // ---------- 发消息 ----------
            SectionHeader(stringResource(R.string.wifi_test_send_section))
            GroupCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = stringResource(R.string.wifi_test_content_label),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            cornerRadius = 14.dp,
                            modifier = Modifier.weight(1f)
                        )
                        HGap(8)
                        Button(
                            onClick = {
                                val text = draft.ifBlank { "ping ${System.currentTimeMillis() % 100000}" }
                                val env = Envelope(
                                    type = MsgType.CHAT,
                                    sender = com.nekochat.chat.DeviceInfo("test", localDeviceName),
                                    text = text
                                )
                                val frame = Wire.encode(env)
                                draft = ""
                                // 必须切到 IO 线程：TCP 的 socket 写操作在主线程会抛
                                // NetworkOnMainThreadException 直接闪退（真机已复现）。
                                // 蓝牙的 BluetoothSocket 不受这个限制，所以这个坑只在 WiFi 下暴露。
                                scope.launch {
                                    val targets = listOfNotNull(
                                        serverLink?.takeIf { it.isConnected },
                                        clientLink?.takeIf { it.isConnected }
                                    )
                                    var sent = 0
                                    withContext(Dispatchers.IO) {
                                        targets.forEach { link ->
                                            link.write(frame) { ok -> if (ok) sent++ }
                                        }
                                    }
                                    append(sentText.format(text, sent, targets.size))
                                }
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            cornerRadius = 16.dp,
                            minHeight = 38.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text(stringResource(R.string.wifi_test_send))
                        }
                    }
                }
            }

            // ---------- 日志 ----------
            SectionHeader(stringResource(R.string.wifi_test_log_section))
            GroupCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (log.isEmpty()) {
                        Text(
                            text = stringResource(R.string.wifi_test_no_events),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            log.forEach { line ->
                                Text(
                                    text = line,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
