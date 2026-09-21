package com.nekochat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekochat.R
import com.nekochat.chat.ChatViewModel
import com.nekochat.chat.TransportType
import com.nekochat.chat.UiState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：昵称、传输方式、组网开关、后台保活。
 *
 * 用 Miuix 的 Preference 组件（SwitchPreference / ArrowPreference）承载行内容，
 * 由 Card 分组，避免自己拼列表行造成的观感不一致。
 */
@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: ChatViewModel,
    onExit: () -> Unit,
    onOpenWifiTest: () -> Unit = {},
    /** 悬浮底栏占用的高度。列表末尾留出，避免最后一项被玻璃盖住。 */
    bottomReserved: Dp = 0.dp
) {
    var nickname by remember(state.nickname) { mutableStateOf(state.nickname) }

    // contentWindowInsets 清零，避免与 PageHeader 的状态栏内边距叠加（会多出一条空白带）
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp + bottomReserved)
        ) {
            PageHeader(
                title = stringResource(R.string.settings_title),
                subtitle = if (state.running) stringResource(R.string.settings_mesh_running)
                else stringResource(R.string.settings_mesh_stopped)
            )

            // ---------- 昵称 ----------
            SectionHeader(stringResource(R.string.settings_nickname_section))
            GroupCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    TextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.settings_nickname_label),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        cornerRadius = 14.dp
                    )
                    VGap(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.setNickname(nickname) },
                            enabled = nickname.isNotBlank() && nickname != state.nickname,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            cornerRadius = 16.dp,
                            minHeight = 38.dp,
                            insideMargin = PaddingValues(horizontal = 18.dp, vertical = 9.dp)
                        ) {
                            Text(stringResource(R.string.settings_save), style = MiuixTheme.textStyles.footnote1)
                        }
                        HGap(10)
                        Text(
                            text = stringResource(R.string.settings_nickname_hint),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            // ---------- 传输方式 ----------
            SectionHeader(stringResource(R.string.settings_transport_section))
            GroupCard {
                TransportType.entries.forEachIndexed { index, type ->
                    if (index > 0) GroupedDivider()
                    TransportOption(
                        type = type,
                        selected = state.transport == type,
                        onSelect = { viewModel.setTransport(type) }
                    )
                }
                GroupedDivider()
                Text(
                    text = stringResource(R.string.settings_transport_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // ---------- 组网 ----------
            SectionHeader(stringResource(R.string.settings_mesh_section))
            GroupCard {
                SwitchPreference(
                    title = stringResource(R.string.settings_auto_start_title),
                    summary = stringResource(R.string.settings_auto_start_summary),
                    checked = state.autoStart,
                    onCheckedChange = { viewModel.setAutoStart(it) }
                )
                GroupedDivider()
                SwitchPreference(
                    title = stringResource(R.string.settings_keep_alive_title),
                    summary = stringResource(R.string.settings_keep_alive_summary),
                    checked = state.notifyOnMessage,
                    onCheckedChange = { viewModel.setNotifyOnMessage(it) }
                )
            }

            // ---------- 操作 ----------
            SectionHeader(stringResource(R.string.settings_actions_section))
            GroupCard {
                Row(modifier = Modifier.padding(14.dp)) {
                    Button(
                        onClick = { viewModel.toggleRunning() },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        cornerRadius = 16.dp,
                        minHeight = 40.dp,
                        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(
                            if (state.running) stringResource(R.string.settings_stop_mesh)
                            else stringResource(R.string.settings_start_mesh),
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                    HGap(10)
                    Button(
                        onClick = { viewModel.clearMessages() },
                        colors = ButtonDefaults.buttonColors(),
                        cornerRadius = 16.dp,
                        minHeight = 40.dp,
                        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(stringResource(R.string.settings_clear_messages), style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }

            // ---------- 诊断 ----------
            SectionHeader(stringResource(R.string.settings_diagnostics_section))
            GroupCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_wifi_test_title),
                    summary = stringResource(R.string.settings_wifi_test_summary),
                    onClick = onOpenWifiTest
                )
            }

            // ---------- 关于 ----------
            SectionHeader(stringResource(R.string.settings_about_section))
            GroupCard {
                InfoRow(stringResource(R.string.settings_online_devices_label), "${state.peers.size}")
                GroupedDivider()
                InfoRow(stringResource(R.string.settings_current_status_label), state.status)
                GroupedDivider()
                InfoRow(stringResource(R.string.settings_transport_label), stringResource(state.transport.labelRes))
                GroupedDivider()
                InfoRow(stringResource(R.string.settings_ui_library_label), "Miuix 0.9.3")
            }

            VGap(14)
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.settings_exit_app),
                    onClick = onExit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PageHorizontalPadding),
                    colors = ButtonDefaults.textButtonColors()
                )
            }
        }
    }
}

/** 传输方式选项行：选中态用主色圆点 + 勾。 */
@Composable
private fun TransportOption(type: TransportType, selected: Boolean, onSelect: () -> Unit) {
    ArrowPreference(
        title = stringResource(type.labelRes),
        summary = when (type) {
            TransportType.RFCOMM -> stringResource(R.string.settings_transport_rfcomm_summary)
            TransportType.BLE -> stringResource(R.string.settings_transport_ble_summary)
            TransportType.WIFI -> stringResource(R.string.settings_transport_wifi_summary)
        },
        onClick = { if (!selected) onSelect() },
        endActions = {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = stringResource(R.string.settings_selected),
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    )
}

/** 键值信息行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f)
        )
    }
}
