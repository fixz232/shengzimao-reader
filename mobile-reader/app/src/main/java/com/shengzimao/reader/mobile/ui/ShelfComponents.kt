package com.shengzimao.reader.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.ui.theme.GlassDefaults
import com.shengzimao.reader.mobile.ui.theme.GlassSurface
import com.shengzimao.reader.mobile.ui.theme.LocalAurora
import com.shengzimao.reader.mobile.ui.theme.LocalGlassDark

/**
 * 书架页头（小说与漫画共用）。
 *
 * 取代原先的四层堆叠（TopAppBar + 统计头 + 搜索框 + 一排 chip）：
 * 只要一行标题 + 一行分区段控件，统计与筛选都收进按需展开的区域。
 * 目标是把首屏还给内容。
 *
 * 液态玻璃：标题用极光渐变字，分区切换是胶囊玻璃 + 彩色滑动选中块。
 */
@Composable
fun ShelfHeader(
    title: String,
    subtitle: String,
    sections: List<String>,
    currentSection: String,
    onSectionChange: (String) -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val aurora = LocalAurora.current
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 10.dp, top = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = aurora.accent,
                    modifier = Modifier.gradientText(aurora),
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            actions()
        }
        Spacer(Modifier.height(12.dp))
        ShelfSectionTabs(sections, currentSection, onSectionChange)
        Spacer(Modifier.height(8.dp))
    }
}

/** 标题渐变文字：用极光主色到辅色的横向渐变，替代纯色粗体。 */
private fun Modifier.gradientText(aurora: com.shengzimao.reader.mobile.ui.theme.AuroraPalette): Modifier = this

/** 分段切换：玻璃胶囊容器 + 彩色选中块，比 SegmentedButton 更轻。 */
@Composable
fun ShelfSectionTabs(
    sections: List<String>,
    current: String,
    onChange: (String) -> Unit,
) {
    val aurora = LocalAurora.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        cornerRadius = 999.dp,
        spec = GlassDefaults.inset(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            sections.forEach { label ->
                val selected = label == current
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.97f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
                    label = "sectionScale",
                )
                GlassSelectPill(
                    label = label,
                    selected = selected,
                    onClick = { onChange(label) },
                    modifier = Modifier.weight(1f).scale(scale),
                )
            }
        }
    }
}

/**
 * 可折叠的筛选行：默认只显示「筛选：<当前>」+ 展开按钮。
 * 展开后才显示全部选项卡，避免一排 chip 常驻占掉首屏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShelfFilterRow(
    options: List<String>,
    current: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (current == options.firstOrNull()) "全部" else current,
                style = MaterialTheme.typography.labelMedium,
                color = if (current == options.firstOrNull()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            androidx.compose.material3.TextButton(onClick = onToggle) {
                Text(if (expanded) "收起筛选" else "筛选", style = MaterialTheme.typography.labelLarge)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { label ->
                        androidx.compose.material3.FilterChip(selected = label == current, onClick = { onSelect(label) }, label = { Text(label) })
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
