package com.nekochat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekochat.R
import com.nekochat.chat.ChatMessage
import com.nekochat.chat.ChatViewModel
import com.nekochat.chat.TransportType
import com.nekochat.chat.UiState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 聊天主界面：状态栏 + 消息列表 + 输入区。
 *
 * 消息按「同一发送者的连续消息」成组，组内只补偿一次发送者名字；
 * 气泡贴边侧用尖角、另一侧用大圆角，形成对话方向感。
 */
@Composable
fun ChatScreen(
    state: UiState,
    viewModel: ChatViewModel,
    permissionRequested: Boolean,
    onPermissionRequested: () -> Unit,
    /** 悬浮底栏占用的高度。列表末尾与输入框各自留出，避免被玻璃盖住。 */
    bottomReserved: Dp = 0.dp
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // stringResource 只能在 composable 作用域调用，而 subtitle 的 buildString 是普通 lambda，
    // 所以传输方式名和在线设备数先在这里取出来。
    val transportLabel = stringResource(state.transport.labelRes)
    val onlineSummary = if (state.connectedCount == 0) {
        stringResource(R.string.chat_no_peers_online)
    } else {
        stringResource(R.string.chat_peers_online, state.connectedCount)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // contentWindowInsets 必须清零：外层 Scaffold 已经处理过系统栏，
    // 而 PageHeader 自己会加状态栏内边距。若这里保留默认的 systemBars，
    // 状态栏高度会被叠加两次，标题上方出现一大条空白。
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            PageHeader(
                title = if (state.privateTarget == null) {
                    stringResource(R.string.chat_title)
                } else {
                    state.privatePeerName ?: stringResource(R.string.chat_private)
                },
                subtitle = buildString {
                    append(transportLabel)
                    append(" · ")
                    append(onlineSummary)
                },
                actions = {
                    TextButton(
                        text = if (state.running) {
                            stringResource(R.string.chat_stop)
                        } else {
                            stringResource(R.string.chat_start)
                        },
                        onClick = { viewModel.toggleRunning() },
                        colors = if (state.running) {
                            ButtonDefaults.textButtonColors()
                        } else {
                            ButtonDefaults.textButtonColorsPrimary()
                        }
                    )
                }
            )

            EnvironmentBanner(
                state = state,
                permissionRequested = permissionRequested,
                onRequestPermission = onPermissionRequested,
                onStart = { viewModel.start() }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    EmptyConversation(
                        transport = state.transport,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    // 列表末尾留出底栏高度：消息可以滑到玻璃**下面**（这样玻璃有内容可透），
                    // 但最后一条仍能滑到玻璃上方，不会被永久遮住
                    MessageList(
                        state = state,
                        listState = listState,
                        extraBottom = bottomReserved
                    )
                }
            }

            if (state.privateTarget != null) {
                PrivateTargetBar(
                    name = state.privatePeerName ?: stringResource(R.string.chat_peer_placeholder),
                    onClear = { viewModel.setPrivateTarget(null) }
                )
            }

            // 输入框也要让开底栏，否则玻璃会盖住它、点不到
            Box(modifier = Modifier.padding(bottom = bottomReserved)) {
                InputBar(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    enabled = state.running
                )
            }
        }
    }
}

/**
 * 空会话引导。
 *
 * 不用裸文字占满整屏（那样显得空旷），改为「图标 + 标题 + 三条步骤卡片」，
 * 把中间那片空白填成有信息量的内容，也给用户明确的上手路径。
 */
@Composable
private fun EmptyConversation(transport: TransportType, modifier: Modifier = Modifier) {
    // 三种传输的「怎么连上」差别很大，第 2 步必须按当前方式给对应的说法
    val startSummary = stringResource(
        when (transport) {
            TransportType.RFCOMM -> R.string.chat_setup_start_rfcomm
            TransportType.BLE -> R.string.chat_setup_start_ble
            TransportType.WIFI -> R.string.chat_setup_start_wifi
        }
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 图标：柔和的圆形底衬
        Surface(
            shape = CircleShape,
            color = MiuixTheme.colorScheme.tertiaryContainer
        ) {
            Box(
                modifier = Modifier.size(76.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Messages,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        VGap(18)
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onBackground
        )
        VGap(6)
        Text(
            text = stringResource(R.string.chat_empty_summary),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            textAlign = TextAlign.Center
        )
        VGap(28)

        // 上手步骤：与「设置」里的分组卡片同款观感
        GroupCard {
            SetupStep(
                1,
                stringResource(R.string.chat_setup_transport_title),
                stringResource(R.string.chat_setup_transport_summary)
            )
            GroupedDivider()
            SetupStep(
                2,
                stringResource(R.string.chat_setup_start_title),
                startSummary
            )
            GroupedDivider()
            SetupStep(
                3,
                stringResource(R.string.chat_setup_chat_title),
                stringResource(R.string.chat_setup_chat_summary)
            )
        }
    }
}

/** 上手步骤的一行。 */
@Composable
private fun SetupStep(index: Int, title: String, summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 步骤序号：小圆形徽标
        Surface(
            shape = CircleShape,
            color = MiuixTheme.colorScheme.tertiaryContainer
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$index",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }
        HGap(12)
        Column {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/** 消息列表：插入日期分隔，并对连续同发送者消息做分组。 */
@Composable
private fun MessageList(
    state: UiState,
    listState: LazyListState,
    extraBottom: Dp = 0.dp
) {
    val messages = state.messages
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PageHorizontalPadding,
            end = PageHorizontalPadding,
            top = 8.dp,
            bottom = 8.dp + extraBottom
        ),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
            val previous = messages.getOrNull(index - 1)
            val newDay = previous == null || !isSameDay(previous.timestamp, message.timestamp)
            // 先判空再取字段，让编译器收窄类型（不需要 !!
            val continuesPrevious = previous != null && !newDay &&
                previous.senderId == message.senderId &&
                previous.outgoing == message.outgoing &&
                previous.targetId == message.targetId

            Column {
                if (newDay) {
                    DateSeparator(message.timestamp)
                }
                MessageBubble(
                    message = message,
                    // 组内后续消息不重复显示标签行。
                    //
                    // 标签行不能因为「处于私聊模式」就整个藏掉 ——
                    // 「私聊」+ 锁图标是挂在它上面的，藏了用户就分不清哪条是私聊。
                    // 具体显示什么由 MessageBubble 决定（见 isGroupStart）。
                    isGroupStart = !continuesPrevious,
                    peerNameOf = { deviceId ->
                        state.peers.firstOrNull { it.deviceId == deviceId }?.nickname ?: deviceId
                    }
                )
            }
        }
    }
}

/** 日期分隔条。 */
@Composable
private fun DateSeparator(timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = MiuixTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                text = formatDate(timestamp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/** 单条消息气泡。 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    isGroupStart: Boolean,
    peerNameOf: (String) -> String = { it }
) {
    val outgoing = message.outgoing
    // 私聊消息（targetId 非空）用独立配色，与群发区分。
    // 只用颜色区分不够 —— 色盲用户看不出来，浅色主题下差异也弱，
    // 所以同时加一个带锁图标的「私聊」标签。
    val privateChat = message.targetId != null

    val showLabel = shouldShowMessageLabel(
        outgoing = outgoing,
        system = message.system,
        targetId = message.targetId,
        isGroupStart = isGroupStart
    )

    val bubbleColor = when {
        message.system -> MiuixTheme.colorScheme.surfaceContainerHigh
        outgoing -> MiuixTheme.colorScheme.primary
        privateChat -> MiuixTheme.colorScheme.secondaryContainer
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val textColor = when {
        message.system -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        outgoing -> MiuixTheme.colorScheme.onPrimary
        privateChat -> MiuixTheme.colorScheme.onSecondaryContainer
        else -> MiuixTheme.colorScheme.onSurface
    }
    val accentColor = if (privateChat) {
        MiuixTheme.colorScheme.secondary
    } else {
        MiuixTheme.colorScheme.primary
    }
    // 贴边侧尖角、另一侧大圆角，形成对话方向感
    val shape = if (outgoing) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
            if (showLabel) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        start = if (outgoing) 0.dp else 12.dp,
                        end = if (outgoing) 12.dp else 0.dp,
                        bottom = 3.dp
                    )
                ) {
                    // 私聊时显示「对方名 · 私聊」，让对方是谁一目了然；
                    // 发出的私聊则显示收件人，避免不知道私聊发给了谁
                    Text(
                        text = when {
                            // privateChat 等价于 targetId != null；用 ?.let 取值，
                            // 既不必写 !!，也不会触发「条件恒真」告警
                            privateChat && outgoing -> stringResource(
                                R.string.chat_sent_to,
                                peerNameOf(message.targetId)
                            )
                            else -> message.senderName
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = accentColor
                    )
                    if (privateChat) {
                        Text(
                            text = stringResource(R.string.chat_private_suffix),
                            style = MiuixTheme.textStyles.footnote2,
                            color = accentColor
                        )
                        HGap(3)
                        Icon(
                            imageVector = MiuixIcons.Lock,
                            contentDescription = stringResource(R.string.chat_private),
                            tint = accentColor,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.widthIn(max = 284.dp),
                shape = shape,
                color = bubbleColor
            ) {
                Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                    Text(
                        text = message.text,
                        style = MiuixTheme.textStyles.main,
                        color = textColor
                    )
                    Text(
                        text = if (outgoing) {
                            "${formatTime(message.timestamp)} · ${deliveryText(message)}"
                        } else {
                            formatTime(message.timestamp)
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = textColor.copy(alpha = 0.62f),
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun deliveryText(message: ChatMessage): String = when {
    message.targetId != null && message.deliveredCount > 0 -> stringResource(R.string.chat_delivery_delivered)
    message.targetId != null -> stringResource(R.string.chat_delivery_sending)
    message.expectedCount == 0 -> stringResource(R.string.chat_delivery_no_peers)
    message.deliveredCount >= message.expectedCount ->
        stringResource(R.string.chat_delivery_broadcast, message.deliveredCount)
    else -> stringResource(R.string.chat_delivery_sent, message.deliveredCount)
}

/**
 * 是否显示气泡上方那行标签（发送者 / 「私聊」+ 锁图标）。
 *
 * 抽成纯函数是为了能被单元测试覆盖 —— 私聊的视觉效果需要两台设备才能人工确认，
 * 但这条规则本身不该靠「看着对」来保证。
 *
 * 规则：
 * - 非组首不显示（连续同一来源的消息只在第一条上标一次）
 * - 系统消息不显示
 * - 收到的消息显示（告诉你是谁说的）
 * - 发出的私聊显示（否则不知道悄悄话说给了谁）
 * - 发出的群发不显示（自己的名字没有信息量）
 */
internal fun shouldShowMessageLabel(
    outgoing: Boolean,
    system: Boolean,
    targetId: String?,
    isGroupStart: Boolean
): Boolean {
    if (system) return false
    if (!isGroupStart) return false
    return !outgoing || targetId != null
}

/** 权限 / 蓝牙 / 运行状态提示条。 */
@Composable
private fun EnvironmentBanner(
    state: UiState,
    permissionRequested: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit
) {
    val hint: String?
    var actionLabel: String? = null
    var action: (() -> Unit)? = null

    when {
        !state.bluetoothSupported -> hint = stringResource(R.string.chat_bt_unsupported)

        !state.permissionGranted -> {
            hint = if (permissionRequested) {
                stringResource(R.string.chat_bt_permission_denied)
            } else {
                stringResource(R.string.chat_bt_permission_required)
            }
            if (!permissionRequested) {
                actionLabel = stringResource(R.string.chat_grant)
                action = onRequestPermission
            }
        }

        !state.bluetoothEnabled -> hint = stringResource(R.string.chat_bt_disabled)

        // 只在 BLE 上提示：只有 BLE 扫描依赖定位能力，RFCOMM 走配对、WiFi 走 TCP 都不需要。
        // 不提示的话，Android 11 及以下会出现"权限都给了、就是扫不到设备"的无声失败。
        state.transport == TransportType.BLE && !state.locationServiceEnabled ->
            hint = stringResource(R.string.chat_location_disabled)

        !state.running -> {
            hint = stringResource(
                R.string.chat_networking_not_started,
                stringResource(state.transport.labelRes)
            )
            actionLabel = stringResource(R.string.chat_start)
            action = onStart
        }

        else -> hint = null
    }

    if (hint == null) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MiuixTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = hint,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            )
            if (actionLabel != null && action != null) {
                TextButton(
                    text = actionLabel,
                    onClick = action,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

/** 私聊模式提示条。 */
@Composable
private fun PrivateTargetBar(name: String, onClear: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MiuixTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_visible_only_to, name),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.chat_exit_private),
                    tint = MiuixTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 底部输入栏。 */
@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PageHorizontalPadding, end = 10.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        TextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            label = if (enabled) {
                stringResource(R.string.chat_input_hint)
            } else {
                stringResource(R.string.chat_input_disabled_hint)
            },
            useLabelAsPlaceholder = true,
            enabled = enabled,
            singleLine = false,
            maxLines = 4,
            cornerRadius = 20.dp,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() })
        )
        Box(modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)) {
            Button(
                onClick = onSend,
                enabled = enabled && draft.isNotBlank(),
                colors = ButtonDefaults.buttonColorsPrimary(),
                cornerRadius = 22.dp,
                minWidth = 48.dp,
                minHeight = 44.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 时间格式化
// ---------------------------------------------------------------------------

private fun isSameDay(a: Long, b: Long): Boolean {
    val calA = Calendar.getInstance().apply { timeInMillis = a }
    val calB = Calendar.getInstance().apply { timeInMillis = b }
    return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
        calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun formatDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val pattern = when {
        isSameDay(timestamp, now) -> stringResource(R.string.chat_date_today)
        cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) -> stringResource(R.string.chat_date_this_year)
        else -> stringResource(R.string.chat_date_full)
    }
    // 读 Locale 放在普通函数里：在 @Composable 里读 Locale.getDefault() 不会被 Compose
    // 观察到，语言切换时不会重组（lint 的 NonObservableLocale）
    return formatWithPattern(timestamp, pattern)
}

private fun formatWithPattern(timestamp: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
