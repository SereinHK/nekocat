package com.nekochat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 共用的 UI 构件与间距常量。
 *
 * 页面统一遵循 HyperOS 的节奏：左右 16dp 外边距、分组小标题、圆角 16dp 的卡片、
 * 行高约 48dp 的分组列表，行之间用不顶边的细分隔线。
 */

/** 页面内容的统一水平边距。 */
val PageHorizontalPadding = 16.dp

/** 分组卡片的圆角。 */
val CardCornerRadius = 16.dp

/**
 * 页面头部。
 *
 * 不使用 `SmallTopAppBar`：它的标题在「扣除 actions 后的剩余空间」里居中，
 * 一旦右侧放按钮，标题视觉上就会偏到一边。这里用左标题 + 右操作的标准排布。
 *
 * **状态栏内边距只在这里加一层。**
 * 各页面的 `Scaffold` 已经把 `contentWindowInsets` 清零了（避免重复），
 * 所以整棵树里只有这一处负责让开状态栏。内边距加在 [Surface] 外层，
 * 这样头部背景能延伸到状态栏下方，而文字落在状态栏之下。
 */
@Composable
fun PageHeader(
    title: String,
    // modifier 放在第一个可选参数位置：Compose 的公开组件约定，
    // 否则调用方按位置传参会落到 subtitle 上（当前调用都用具名参数，所以只是规范问题）
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        color = MiuixTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // title4 = 18sp：比 title3 紧凑，更接近 HyperOS 应用栏的字号
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        // 用 onSurfaceSecondary（#CC000000）而非 onBackgroundVariant，
                        // 后者偏淡，在纯白头部上读起来发虚
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                content = actions
            )
        }
    }
}

/** 分组小标题。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = text,
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/** 分组卡片容器：把若干行内容包进一个圆角 Surface。 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding),
        shape = RoundedCornerShape(CardCornerRadius),
        // 卡片用纯白：页面底色是 #F7F7F7，白卡片浮在上面才有层次
        color = MiuixTheme.colorScheme.surfaceVariant
    ) {
        Column(content = content)
    }
}

/** 卡片内的行分隔线（不顶到左右边缘）。 */
@Composable
fun GroupedDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .height(0.7.dp)
            // 用调色板里专为分隔线准备的颜色，比自己调 alpha 更贴近系统观感
            .background(MiuixTheme.colorScheme.dividerLine)
    )
}

/** 卡片内一行的通用容器。 */
@Composable
fun GroupRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** 状态小圆点。 */
@Composable
fun StatusDot(color: Color, size: Int = 9) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** 空状态提示。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 24.dp)
    )
}

/** 横向间距。 */
@Composable
fun HGap(width: Int) {
    Spacer(modifier = Modifier.width(width.dp))
}

/** 纵向间距。 */
@Composable
fun VGap(height: Int) {
    Spacer(modifier = Modifier.height(height.dp))
}
