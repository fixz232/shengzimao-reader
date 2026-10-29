package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.ui.theme.GlassDefaults
import com.shengzimao.reader.mobile.ui.theme.GlassSurface
import com.shengzimao.reader.mobile.ui.theme.LocalAurora
import com.shengzimao.reader.mobile.ui.theme.LocalGlassDark

/**
 * 全应用共享的 UI 组件。
 *
 * 设计系统落地在这些组件里：页面不再各自写 Surface + padding + 圆角，
 * 而是统一用 [AppCard] / [AppSectionTitle] / [AppSearchField] 等，
 * 保证所有页面圆角、间距、字重一致。
 *
 * 液态玻璃版本的做法：容器统一用 [GlassSurface]（半透明底 + 左上高光 + 亮描边），
 * 页面本身保持透明，让 [com.shengzimao.reader.mobile.ui.theme.LiquidGlassBackground]
 * 铺的极光色透上来——这是「玻璃」成立的前提，千万不要给页面补不透明底色。
 */

/** 页面内容的标准玻璃卡片。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val tokens = com.shengzimao.reader.mobile.ui.theme.LocalAppTokens.current
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(tokens.radii.card),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(tokens.spacing.card), content = content)
    }
}

/** 页面区块标题：渐变小竖条 + 加粗标题，玻璃上比小圆点更清晰。 */
@Composable
fun AppSectionTitle(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val aurora = LocalAurora.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 大搜索框：玻璃胶囊，带柔和内发光。 */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    onSearch: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, "清空", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
    )
}

/** 空状态：玻璃圆底图标 + 主标题 + 说明 + 可选按钮。 */
@Composable
fun AppEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val aurora = LocalAurora.current
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            aurora.accent.copy(alpha = 0.30f),
                            aurora.accentAlt.copy(alpha = 0.22f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (aurora.isDark) 0.10f else 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** 页面顶部的统计头：玻璃小块，数字用强调色。 */
@Composable
fun AppStatsHeader(
    title: String,
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.forEach { (value, label) ->
                GlassSurface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    cornerRadius = 16.dp,
                    spec = GlassDefaults.inset(),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            value,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 状态角标：封面上的「读完」「缓存」等标记，玻璃白底 + 深字更通透。 */
@Composable
fun AppBadge(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.primary) {
    // 前景色按容器亮度选，避免浅色底上白字看不清。
    val content = if (container.luminance() > 0.55f) Color(0xFF1A1A1C) else Color.White
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(container)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
    ) {
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 行内提示条：语义底色、清楚的文字和可关闭操作。 */
@Composable
fun AppInlineNotice(
    text: String,
    isError: Boolean,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (onDismiss != null) {
                // 触控目标不小于 48dp（原 30dp 不满足无障碍最小尺寸）。
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, "关闭", Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * 玻璃分页标签栏。
 *
 * 替代 Material 的 [androidx.compose.material3.SecondaryTabRow]：
 * 后者自带不透明的 surface 底色与整行指示条，铺在极光背景上会切出一条白带。
 * 这里用「玻璃胶囊容器 + 选中项强调色渐变块」，不占额外高度，也不会遮住背景。
 */
@Composable
fun GlassTabRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aurora = LocalAurora.current
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        cornerRadius = 999.dp,
        spec = GlassDefaults.inset(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                GlassSelectPill(
                    label = label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 玻璃选中胶囊：选中态不再是「实心彩色块」，而是**透色的玻璃芯片**。
 *
 * 参考图里的选中项都是「一块被强调色染过的玻璃」——能看见底下的背景，
 * 边缘有一道亮线。实心色块会把背景完全盖掉，那样就是 Material 2 的 chip，
 * 不是玻璃。做法：强调色只给 22% 的染色 + 一条亮边 + 白色文字。
 */
@Composable
fun GlassSelectPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aurora = LocalAurora.current
    val dark = LocalGlassDark.current
    val shape = CircleShape
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    // 选中：强调色薄薄染一层，仍然透。
                    selected -> Brush.verticalGradient(
                        listOf(
                            aurora.accent.copy(alpha = if (dark) 0.34f else 0.22f),
                            aurora.accent.copy(alpha = if (dark) 0.18f else 0.12f),
                        ),
                    )
                    // 未选中：几乎无染色。
                    else -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                },
            )
            .then(
                // 用顶部柔光代替描边：描边会形成硬色环，放大看很廉价。
                if (selected) {
                    Modifier.drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (dark) 0.20f else 0.42f),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = size.height * 0.55f,
                            ),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            // 选中用强调色的深色版本（浅底上更好读），而不是纯白。
            color = when {
                selected && dark -> Color.White
                selected -> aurora.accent
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 玻璃胶囊 chip：筛选、标签页都用它，选中态用强调色渐变。 */@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val aurora = LocalAurora.current
    val dark = LocalGlassDark.current
    val shape = CircleShape
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(aurora.accent, aurora.accentAlt))
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.10f else 0.55f),
                            Color.White.copy(alpha = if (dark) 0.06f else 0.35f),
                        ),
                    )
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.30f)
                else if (dark) Color(0x24FFFFFF) else Color(0x80FFFFFF),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            leading?.invoke()
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}
