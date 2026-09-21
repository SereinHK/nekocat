package com.nekochat.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekochat.R
import com.nekochat.chat.ChatViewModel
import com.nekochat.chat.Tab
import com.nekochat.ui.liquid.LiquidGlassBottomBar
import com.nekochat.ui.liquid.LiquidGlassTab
import com.nekochat.util.BtPermissions
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用根组合。
 *
 * 布局分三层，顺序不能乱：
 * 1. 最外层背景；
 * 2. 内容层，挂 [layerBackdrop] 供底栏取景 —— 必须覆盖**全屏**，
 *    否则底栏位置的采样会落在层外，产生黑块；
 * 3. 悬浮底栏（叠加层），因此内容区需为它让出底部高度。
 */
@Composable
fun NekoChatAppRoot(onExit: () -> Unit = {}) {
    // 固定调色板，不用 MonetSystem 动态取色：动态取色会从壁纸提取一套颜色
    // （本机提取出的是发灰的浅紫），把整体配色带脏。
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller) {
        val viewModel: ChatViewModel = viewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        var permissionRequested by remember { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result.values.all { it }
            viewModel.refreshEnvironment(startIfReady = granted)
            if (!granted) {
                viewModel.reportPermissionDenied()
            }
        }

        val requestPermissions: () -> Unit = {
            permissionRequested = true
            permissionLauncher.launch(BtPermissions.required())
        }

        LaunchedEffect(Unit) {
            viewModel.refreshEnvironment(startIfReady = true)
            if (!BtPermissions.allGranted(context) && !permissionRequested) {
                permissionRequested = true
                permissionLauncher.launch(BtPermissions.required())
            }
        }

        // 用带 onDraw 的重载（官方与 SukiSU 都这么用）：
        // 先把不透明底色画进**录制层内部**，再画内容。
        //
        // 之前用无参版 + Modifier.background() 时，底栏渲染成深灰 ——
        // 录制层若含透明像素，模糊会向透明区扩散、颜色被拉暗。
        // 这个重载从根上保证录制层是实心的。
        val pageBackground = MiuixTheme.colorScheme.background
        val backdrop = rememberLayerBackdrop {
            // 先铺满整个录制层，再画内容。
            // 录制层尺寸等于挂载它的 Box（fillMaxSize），所以这里能把底栏下方那片
            // 没有内容的区域也填上底色，玻璃采样过去才不会撞到空白。
            drawRect(pageBackground)
            drawContent()
        }
        val blurSupported = remember { isRuntimeShaderSupported() }
        // 诊断页（WiFi 直连测试）作为覆盖层，盖住主界面
        var showWifiTest by remember { mutableStateOf(false) }
        // 扫码连接页
        var showQrScan by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {
            // ---------- 内容层 ----------
            // 底色由 backdrop 的 onDraw 画进录制层，这里不再加 background。
            //
            // **内容层不加底部 padding** —— 让页面内容一直延伸到底，这样滚动时
            // 内容会从玻璃底栏**下面穿过**，玻璃才有东西可透（否则它只能是一块色斑）。
            // 底栏遮挡的问题由各页面自己解决：列表末尾和输入框各自留出预留高度，
            // 保证最后一项与输入框始终可点。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                when (state.tab) {
                    Tab.CHAT -> ChatScreen(
                        state = state,
                        viewModel = viewModel,
                        permissionRequested = permissionRequested,
                        onPermissionRequested = requestPermissions,
                        bottomReserved = FloatingBarReservedHeight
                    )

                    Tab.DEVICES -> DevicesScreen(
                        state = state,
                        viewModel = viewModel,
                        onScanQr = { showQrScan = true },
                        bottomReserved = FloatingBarReservedHeight
                    )

                    Tab.SETTINGS -> SettingsScreen(
                        state = state,
                        viewModel = viewModel,
                        onExit = onExit,
                        onOpenWifiTest = { showWifiTest = true },
                        bottomReserved = FloatingBarReservedHeight
                    )
                }
            }

            // ---------- 悬浮底栏 ----------
            LiquidGlassBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        start = FloatingBarHorizontalMargin,
                        end = FloatingBarHorizontalMargin,
                        bottom = FloatingBarBottomMargin
                    ),
                selectedIndex = state.tab.ordinal,
                onSelected = { viewModel.selectTab(Tab.entries[it]) },
                backdrop = backdrop,
                tabsCount = Tab.entries.size,
                isBlurEnabled = BLUR_ENABLED && blurSupported
            ) { activateTab ->
                BottomTabItem(
                    state.tab,
                    Tab.CHAT,
                    stringResource(R.string.tab_chat),
                    MiuixIcons.Messages,
                    activateTab
                )
                BottomTabItem(
                    state.tab,
                    Tab.DEVICES,
                    stringResource(R.string.tab_devices),
                    MiuixIcons.Contacts,
                    activateTab
                )
                BottomTabItem(
                    state.tab,
                    Tab.SETTINGS,
                    stringResource(R.string.tab_settings),
                    MiuixIcons.Settings,
                    activateTab
                )
            }

            // ---------- WiFi 直连测试（全屏覆盖） ----------
            if (showWifiTest) {
                // 覆盖层是"页面中的页面"，必须自己接管返回键。
                // 不接管的话系统返回键会直接 finish Activity —— 用户想退出的是一层覆盖，
                // 结果整个 App 退出、组网中断。扫码页尤其容易踩：扫不动时本能就是按返回。
                BackHandler { showWifiTest = false }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background)
                ) {
                    WifiTestScreen(onBack = { showWifiTest = false })
                }
            }

            // ---------- 扫码连接（全屏覆盖） ----------
            if (showQrScan) {
                BackHandler { showQrScan = false }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background)
                ) {
                    QrScanScreen(
                        onBack = { showQrScan = false },
                        onScanned = { endpoint ->
                            showQrScan = false
                            viewModel.connectWifi("${endpoint.host}:${endpoint.port}")
                        }
                    )
                }
            }
        }
    }
}

/** 底栏里的一项：图标 + 文字，选中时用主色。 */
@Composable
private fun RowScope.BottomTabItem(
    current: Tab,
    tab: Tab,
    label: String,
    icon: ImageVector,
    activateTab: (Int) -> Unit
) {
    val selected = current == tab
    val tint = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    LiquidGlassTab(
        selected = selected,
        onClick = { activateTab(tab.ordinal) }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            color = tint
        )
    }
}

// ---------------------------------------------------------------------------
// 悬浮底栏尺寸
// ---------------------------------------------------------------------------

/**
 * 是否启用液态玻璃。
 *
 * **当前关闭**：`drawBackdrop` 的采样在本机会取到偏暗的内容，
 * 胶囊渲染成深灰色（已排除阴影、折射、布局位置三个因素）。
 * 关掉后走不透明胶囊分支，观感干净且在低版本 Android 上一致。
 *
 * 重新启用前请先确认 backdrop 与采样窗口的位置关系正确。
 */
private const val BLUR_ENABLED = true

/** 内容区为悬浮底栏让出的高度（胶囊 64dp + 上下留白）。 */
private val FloatingBarReservedHeight = 92.dp

/** 胶囊左右外边距。 */
private val FloatingBarHorizontalMargin = 24.dp

/** 胶囊与系统手势区之间的间距。 */
private val FloatingBarBottomMargin = 8.dp
