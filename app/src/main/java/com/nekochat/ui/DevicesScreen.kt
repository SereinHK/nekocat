package com.nekochat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nekochat.R
import com.nekochat.bluetooth.WifiTransport
import com.nekochat.chat.ChatViewModel
import com.nekochat.chat.DiscoveredDevice
import com.nekochat.chat.Peer
import com.nekochat.chat.TransportType
import com.nekochat.chat.UiState
import com.nekochat.util.QrPayload
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备页：在线节点、已配对设备、BLE 扫描结果。
 *
 * 每一类都是一个分组卡片，行与行之间用不顶边的分隔线，符合 HyperOS 列表节奏。
 */
@Composable
fun DevicesScreen(
    state: UiState,
    viewModel: ChatViewModel,
    onScanQr: () -> Unit = {},
    /** 悬浮底栏占用的高度。列表末尾留出，避免最后一项被玻璃盖住。 */
    bottomReserved: Dp = 0.dp
) {
    // contentWindowInsets 清零，避免与 PageHeader 的状态栏内边距叠加（会多出一条空白带）
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // 末尾留出底栏高度：内容可以滑到玻璃下面，但最后一项仍可滚到玻璃上方
                .padding(bottom = 16.dp + bottomReserved)
        ) {
            PageHeader(
                title = stringResource(R.string.devices_title),
                subtitle = if (state.peers.isEmpty()) {
                    stringResource(R.string.devices_no_online_devices)
                } else {
                    stringResource(
                        R.string.devices_online_count,
                        state.peers.size,
                        stringResource(state.transport.labelRes)
                    )
                },
                actions = {
                    // WiFi 模式没有「扫描」概念（对端靠手填地址），不显示无意义的按钮
                    if (state.transport != TransportType.WIFI) {
                        TextButton(
                            text = if (state.scanning) {
                                stringResource(R.string.devices_stop_scan)
                            } else {
                                stringResource(R.string.devices_scan)
                            },
                            onClick = {
                                if (state.scanning) viewModel.stopScan() else viewModel.startScan()
                            },
                            colors = if (state.scanning) {
                                ButtonDefaults.textButtonColors()
                            } else {
                                ButtonDefaults.textButtonColorsPrimary()
                            }
                        )
                    }
                }
            )

            // ---------- 在线节点 ----------
            SectionHeader(stringResource(R.string.devices_online_section))
            if (state.peers.isEmpty()) {
                EmptyHint(stringResource(R.string.devices_online_empty))
            } else {
                GroupCard {
                    state.peers.forEachIndexed { index, peer ->
                        if (index > 0) GroupedDivider()
                        PeerRow(
                            peer = peer,
                            isPrivateTarget = state.privateTarget == peer.deviceId,
                            onChat = {
                                viewModel.setPrivateTarget(peer.deviceId)
                                viewModel.selectTab(com.nekochat.chat.Tab.CHAT)
                            }
                        )
                    }
                }
            }

            // ---------- WiFi 局域网 ----------
            // 只在 WiFi 模式显示：其他模式下这些控件没有意义，只会造成困惑
            if (state.transport == TransportType.WIFI) {
                WifiSection(state = state, viewModel = viewModel, onScan = onScanQr)
            }

            // ---------- 已配对设备 ----------
            if (state.transport != TransportType.WIFI) {
                SectionHeader(stringResource(R.string.devices_paired_section))
                if (state.paired.isEmpty()) {
                    EmptyHint(stringResource(R.string.devices_paired_empty))
                } else {
                    GroupCard {
                        state.paired.forEachIndexed { index, device ->
                            if (index > 0) GroupedDivider()
                            DeviceRow(
                                device = device,
                                actionLabel = stringResource(R.string.devices_connect),
                                enabled = state.transport == TransportType.RFCOMM && state.permissionGranted,
                                onAction = { viewModel.connect(device.address) }
                            )
                        }
                    }
                }

                // ---------- 扫描结果 ----------
                SectionHeader(stringResource(R.string.devices_nearby_section))
                if (state.discovered.isEmpty()) {
                    EmptyHint(
                        if (state.transport == TransportType.BLE) {
                            stringResource(R.string.devices_nearby_ble_hint)
                        } else {
                            stringResource(R.string.devices_nearby_classic_hint)
                        }
                    )
                } else {
                    GroupCard {
                        state.discovered.forEachIndexed { index, device ->
                            if (index > 0) GroupedDivider()
                            DeviceRow(
                                device = device,
                                actionLabel = if (device.transport == TransportType.RFCOMM) {
                                    stringResource(R.string.devices_connect)
                                } else {
                                    stringResource(R.string.devices_connecting)
                                },
                                enabled = device.transport == TransportType.RFCOMM,
                                onAction = { viewModel.connect(device.address) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** WiFi 模式的操作卡片：本机地址 / 二维码 / 连接对端。 */
@Composable
private fun WifiSection(
    state: UiState,
    viewModel: ChatViewModel,
    onScan: () -> Unit
) {
    var target by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    // 每次进入设备页都刷新一次本机地址。
    //
    // 不能只在「切换传输方式」时刷新：DHCP 续约或重连会换 IP，
    // 真机上就踩过 —— 界面显示 192.168.31.45，而设备实际已是 192.168.31.5，
    // 照着界面上的地址去连必然超时。地址是会变的，就别缓存。
    LaunchedEffect(Unit) { viewModel.refreshWifiInfo() }

    SectionHeader(stringResource(R.string.devices_local_address_section))
    GroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            val ips = state.wifiLocalIps
            Text(
                text = if (ips.isEmpty()) stringResource(R.string.devices_no_lan_address) else ips.joinToString("\n"),
                style = MiuixTheme.textStyles.main,
                color = if (ips.isEmpty()) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.onSurface
                }
            )
            VGap(6)
            Text(
                // 端口取自监听句柄，但 **IP 用实时列表的第一个**：
                // state.wifiHostAddress 是监听启动那一刻缓存的，DHCP 换址后会变成旧 IP
                // （实测出现过「上面显示 192.168.31.5，下面却说监听 192.168.31.45」）。
                // 服务端绑的是 0.0.0.0，换址后仍可被连接，所以只有显示需要更新。
                text = state.wifiHostAddress?.let { addr ->
                    val port = addr.substringAfterLast(':', "")
                    // 只显示一个地址：系统在部分 ROM 上会同时报出主地址和一个已失效的
                    // 残留地址，列出来只会让用户猜哪个能通。取主地址（列表第一个）。
                    val host = ips.firstOrNull() ?: addr.substringBeforeLast(':')
                    if (port.isEmpty()) {
                        stringResource(R.string.devices_listening, addr)
                    } else {
                        stringResource(R.string.devices_listening_host_port, host, port)
                    }
                } ?: if (state.running) {
                    // 组网在跑却没有地址：说明监听没起来，把状态里的原因显示出来
                    state.status
                } else {
                    stringResource(R.string.devices_start_hint)
                },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            if (state.wifiHostAddress != null) {
                VGap(12)
                Button(
                    onClick = { showQr = true },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    cornerRadius = 16.dp,
                    minHeight = 40.dp,
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(stringResource(R.string.devices_show_qr))
                }
            }
        }
    }

    if (showQr) {
        QrDialog(state = state, viewModel = viewModel, onDismiss = { showQr = false })
    }

    SectionHeader(stringResource(R.string.devices_connect_peer_section))
    GroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            // 扫码是主路径：不用手抄 IP，也就不会抄错、不会遇到 DHCP 换址后连不上
            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColorsPrimary(),
                cornerRadius = 16.dp,
                minHeight = 44.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.devices_scan_peer_qr))
            }
            VGap(14)
            Text(
                text = stringResource(R.string.devices_or_manual_address),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            VGap(8)
            TextField(
                value = target,
                onValueChange = { target = it },
                label = stringResource(R.string.devices_peer_address_label),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 14.dp,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            VGap(6)
            Text(
                text = stringResource(R.string.devices_manual_address_hint, WifiTransport.DEFAULT_PORT),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            VGap(12)
            Button(
                onClick = {
                    keyboard?.hide()
                    viewModel.connectWifi(target)
                },
                enabled = target.isNotBlank(),
                colors = ButtonDefaults.buttonColors(),
                cornerRadius = 16.dp,
                minHeight = 40.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(stringResource(R.string.devices_connect))
            }
        }
    }
}

/** 展示本机连接二维码的弹窗。 */
@Composable
private fun QrDialog(state: UiState, viewModel: ChatViewModel, onDismiss: () -> Unit) {
    // remember 的 key 用「可能变化的全部输入」，避免地址后到导致码是旧的
    val endpoint = remember(state.wifiHostAddress, state.nickname, state.wifiLocalIps) {
        viewModel.wifiEndpoint()
    }
    val bitmap = remember(endpoint) {
        endpoint?.let { QrPayload.toBitmap(QrPayload.encode(it), 720) }
    }

    // miuix 0.9.3 里没有 SuperDialog，这里用 Dialog + miuix 的 Surface 自己搭，
    // 避免依赖不确定的组件 API
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MiuixTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.devices_qr_title),
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface
                )
                VGap(16)
                if (bitmap != null && endpoint != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.devices_qr_content_desc),
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    VGap(14)
                    Text(
                        text = endpoint.nickname,
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    VGap(4)
                    Text(
                        text = endpoint.display,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    VGap(10)
                    Text(
                        text = stringResource(R.string.devices_qr_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                } else {
                    Text(
                        text = if (state.running) state.status else stringResource(R.string.devices_not_listening_hint),
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                VGap(18)
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    cornerRadius = 16.dp,
                    minHeight = 40.dp,
                    insideMargin = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(stringResource(R.string.devices_close))
                }
            }
        }
    }
}

/** 在线节点行。 */
@Composable
private fun PeerRow(peer: Peer, isPrivateTarget: Boolean, onChat: () -> Unit) {
    GroupRow {
        StatusDot(MiuixTheme.colorScheme.primary)
        HGap(12)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.nickname,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.devices_peer_subtitle,
                    stringResource(peer.transport.labelRes),
                    peer.address
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            color = if (isPrivateTarget) {
                MiuixTheme.colorScheme.secondaryContainer
            } else {
                MiuixTheme.colorScheme.primaryContainer
            },
            onClick = onChat
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = MiuixIcons.Messages,
                    contentDescription = null,
                    tint = if (isPrivateTarget) {
                        MiuixTheme.colorScheme.onSecondaryContainer
                    } else {
                        MiuixTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(15.dp)
                )
                HGap(5)
                Text(
                    text = if (isPrivateTarget) {
                        stringResource(R.string.devices_exit_private)
                    } else {
                        stringResource(R.string.devices_private_chat)
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (isPrivateTarget) {
                        MiuixTheme.colorScheme.onSecondaryContainer
                    } else {
                        MiuixTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
        }
    }
}

/** 可连接设备行。 */
@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit
) {
    // stringResource 只能在 composable 作用域调用，而下面 buildString 的 lambda 不是，
    // 所以「已配对」先在这里取出来。
    val bondedLabel = stringResource(R.string.devices_bonded)
    GroupRow {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(device.address)
                    if (device.bonded) append(" · " + bondedLabel)
                    device.rssi?.let { append(" · $it dBm") }
                },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HGap(12)
        Button(
            onClick = onAction,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(),
            cornerRadius = 16.dp,
            minWidth = 64.dp,
            minHeight = 34.dp,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(actionLabel, style = MiuixTheme.textStyles.footnote1)
        }
    }
}
