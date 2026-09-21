// 参考 compose-miuix-ui 官方示例（IosLiquidGlassNavigationBar），Apache-2.0。
//
// 与最初那版的关键差别：用 `drawBackdrop` 手动驱动效果链，并把 padding 撑够——
// 这正是消除边缘黑带的关键（padding 不够时模糊/折射会在边缘被裁，露出黑边）。

package com.nekochat.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 玻璃边缘的镜面高光。 */
private val barSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

/** 单个 tab：图标在上、文字在下，整块可点。 */
@Composable
fun RowScope.LiquidGlassTab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            // 必须是真正的 clickable，而不是只写 semantics { onClick { } }。
            // semantics 只声明无障碍动作，不会消费指针事件——点击会被静默丢弃。
            .clip(RoundedCornerShape(28.dp))
            .clickable(
                onClick = onClick,
                role = Role.Tab,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
            }
            .fillMaxHeight()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/**
 * 液态玻璃悬浮底栏。
 *
 * @param backpack 挂在全屏内容层上的取景背景
 * @param isBlurEnabled false 时退化为普通半透明胶囊（低版本或不支持 RuntimeShader 时）
 */
@Composable
fun LiquidGlassBottomBar(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    isBlurEnabled: Boolean = true,
    content: @Composable RowScope.((Int) -> Unit) -> Unit
) {
    val pillShape = remember { CircleShape }
    val accentColor = MiuixTheme.colorScheme.primary
    // 玻璃底色：几乎全透明，只留极淡的一层。
    //
    // 原来用 surfaceContainer @0.4 —— 那是接近纯白的灰白，把玻璃整个染白了：
    // 胶囊内部实测 R255,G255,G255，等于完全不透背景，也就不像玻璃了。
    // 玻璃的质感应当来自模糊与折射，底色只负责轻微提亮以保证文字可读性。
    val containerColor = if (isBlurEnabled) {
        // 几乎不加色：玻璃内部亮度应当逼近页面背景，否则会像一块亮斑贴在底栏上。
        MiuixTheme.colorScheme.surface.copy(alpha = 0.03f)
    } else {
        MiuixTheme.colorScheme.surfaceVariant
    }
    // isBlurEnabled 只在效果链真正启用时才走 drawBackdrop；否则直接用纯色胶囊。
    val useBackdrop = isBlurEnabled

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val onSelectedUpdated by rememberUpdatedState(onSelected)

    // 指示器位置用 Animatable 做弹簧动画：切换 tab 时滑动而不是瞬移
    val indicator = remember { Animatable(selectedIndex.toFloat()) }
    val tabSpring = remember { spring<Float>(dampingRatio = 0.8f, stiffness = 400f) }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) {
            currentIndex = selectedIndex
            indicator.animateTo(selectedIndex.toFloat(), tabSpring)
        }
    }

    fun activateTab(index: Int) {
        if (index !in 0 until tabsCount) return
        if (currentIndex != index) {
            currentIndex = index
            onSelectedUpdated(index)
        }
        scope.launch { indicator.animateTo(index.toFloat(), tabSpring) }
    }

    // 必须 fillMaxWidth：胶囊要撑满外层给的空间，内部 tab 才能用 weight(1f) 均分。
    // 用 IntrinsicSize.Min 会被压成"内容最小宽度"，三个 tab 挤成一团。
    Box(modifier = modifier.fillMaxWidth()) {
        // 底层：玻璃（或普通底）
        Box(
            modifier = Modifier
                .matchParentSize()
                .onGloballyPositioned { coords ->
                    val contentWidth = coords.size.width.toFloat() - with(density) { 8.dp.toPx() }
                    tabWidthPx = (contentWidth / tabsCount).coerceAtLeast(0f)
                }
                .then(
                    Modifier.dropShadow(
                        shape = pillShape,
                        shadow = Shadow(radius = 10.dp, color = Color.Black, alpha = 0.10f),
                    )
                )
                .then(
                    if (useBackdrop) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                // 不要再手动改 padding。
                                //
                                // 曾经在这里写过 `padding = maxOf(padding, 40.dp.toPx())`
                                // （照抄官方示例），结果胶囊渲染成深灰色 ——
                                // 因为 BackdropEffectScopeImpl 内部用 blurParamOffsets /
                                // shaderOffsetsByTaps 自行管理 padding 并同步采样偏移，
                                // 抢先改大 padding 会让层被撑大而偏移表没跟上，
                                // 采样整体偏到内容之外，取到透明像素。
                                vibrancy()
                                // 模糊半径收小：模糊会在玻璃边缘把周围内容「晕」开，
                                // 晕的范围越大，胶囊上方那片渐变色带就越宽、越像「分隔」。
                                blur(2.dp.toPx(), 2.dp.toPx())
                                lens(
                                    refractionHeight = 18.dp.toPx(),
                                    refractionAmount = 18.dp.toPx(),
                                )
                            },
                            highlight = { barSpecular.copy(alpha = 0.5f) },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, pillShape)
                    }
                )
                .height(64.dp)
        ) {
            // 选中指示器：跟随弹簧动画滑动的浅色胶囊
            if (tabWidthPx > 0f) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .graphicsLayer { translationX = indicator.value * tabWidthPx }
                        .clip(pillShape)
                        .background(accentColor.copy(alpha = 0.15f), pillShape)
                        .height(56.dp)
                        .width(with(density) { tabWidthPx.toDp() })
                )
            }
        }

        // 上层：可点击的 tab 内容
        Row(
            modifier = Modifier
                .height(64.dp)
                .padding(4.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(::activateTab)
        }
    }
}
