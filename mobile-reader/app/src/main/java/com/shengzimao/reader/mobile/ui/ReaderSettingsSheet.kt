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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.data.FontChoice
import com.shengzimao.reader.mobile.data.PageAnimation
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReaderTheme
import com.shengzimao.reader.mobile.data.TurnMode

/**
 * 阅读设置面板：拖动排版滑块时只更新预览，松手后保存并重排一次。
 * 与全局「设置」页面分工：这里只放阅读中要随手调的量。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    onPickFont: () -> Unit,
    onOpenAllSettings: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    var advanced by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<ReadingTypography?>(null) }
    var submitted by remember { mutableStateOf<ReadingTypography?>(null) }
    var fontDragBase by remember { mutableStateOf<ReaderSettings?>(null) }
    val actual = ReadingTypography.from(settings)
    val typography = draft ?: actual
    val preview = typography.applyTo(settings)
    // Keep optimistic edits until the asynchronous per-book save acknowledges them.
    // An older save must not move a thumb back during a second gesture.
    LaunchedEffect(actual) {
        if (draft == actual && fontDragBase == null) {
            draft = null
            submitted = null
        }
    }
    val commitTypography = {
        draft?.let { next ->
            if (next != (submitted ?: actual)) {
                submitted = next
                onUpdate { next.applyTo(it) }
            }
        }
    }
    val flushTypography by rememberUpdatedState { commitTypography() }
    DisposableEffect(Unit) { onDispose { flushTypography() } }
    val close: (() -> Unit) -> Unit = { action -> commitTypography(); action() }
    val update: ((ReaderSettings) -> ReaderSettings) -> Unit = { transform ->
        // Another control can be tapped before the last disk save reaches the UI.
        // Merge the visible typography so that a theme/font edit cannot undo it.
        val pending = draft
        onUpdate { transform(pending?.applyTo(it) ?: it) }
    }
    AppModalSheet(onDismissRequest = { close(onDismiss) }, title = "阅读设置", actions = {
        TextButton(onClick = { close(onOpenAllSettings) }) { Text("全部设置", fontSize = 13.sp) }
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text("阅读页的调整仅用于当前作品", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            onReset?.let { reset -> TextButton(onClick = {
                draft = null; submitted = null; fontDragBase = null
                reset()
            }) { Text("恢复跟随全局设置") } }
            Surface(color = Color(settings.pageBackground), modifier = Modifier.fillMaxWidth().height(148.dp)) {
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(12.dp)) {
                    Text("阅读预览：山间有清风，书中有远方。", color = Color(settings.pageForeground),
                        fontSize = preview.fontSize.sp, lineHeight = (preview.fontSize * preview.lineSpacingPercent / 100f).sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("拖动时预览，松手应用排版", style = MaterialTheme.typography.bodySmall)

            Text("阅读方式", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TurnMode.entries.forEach { mode ->
                    FilterChip(settings.turnMode == mode, { update { it.copy(turnMode = mode) } }, label = { Text(mode.label) })
                }
            }
            Text("左右翻页：向左滑动或点右侧下一页，向右滑动或点左侧上一页，中间打开菜单。", style = MaterialTheme.typography.bodySmall)

            // 字号：- / 数值 / +
            LabeledRow("字号", "${typography.fontSize} sp")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton(Icons.Filled.Remove, "减小字号", enabled = typography.fontSize > 12) {
                    draft = ReadingTypography.from(ReaderSettings.withFontSize(preview, typography.fontSize - 1))
                    commitTypography()
                }
                Slider(
                    value = typography.fontSize.toFloat(),
                    onValueChange = { value ->
                        val base = fontDragBase ?: preview.also { fontDragBase = it }
                        draft = ReadingTypography.from(ReaderSettings.withFontSize(base, value.toInt()))
                    },
                    onValueChangeFinished = { fontDragBase = null; commitTypography() },
                    valueRange = 12f..40f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                StepButton(Icons.Filled.Add, "增大字号", enabled = typography.fontSize < 40) {
                    draft = ReadingTypography.from(ReaderSettings.withFontSize(preview, typography.fontSize + 1))
                    commitTypography()
                }
            }

            LabeledRow("行距", "${typography.lineSpacingPercent}%")
            Slider(
                value = typography.lineSpacingPercent.toFloat(),
                onValueChange = { value -> draft = typography.copy(lineSpacingPercent = (value / 5).toInt() * 5) },
                onValueChangeFinished = { commitTypography() },
                valueRange = 100f..260f,
            )

            LabeledRow("页边距", "${typography.paddingDp} dp")
            Slider(
                value = typography.paddingDp.toFloat(),
                onValueChange = { value -> draft = typography.copy(paddingDp = value.toInt()) },
                onValueChangeFinished = { commitTypography() },
                valueRange = 8f..40f,
            )

            Spacer(Modifier.height(10.dp))
            Text("底色", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    ThemeDot(
                        theme = theme,
                        settings = settings,
                        selected = settings.theme == theme,
                        onClick = { update { s -> s.copy(theme = theme) } },
                    )
                }
            }
            if (settings.theme == ReaderTheme.CUSTOM) {
                Spacer(Modifier.height(10.dp))
                CustomColorRow(settings, update)
            }

            Spacer(Modifier.height(10.dp))
            Text("字体", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChoice.entries.filter { it != FontChoice.CUSTOM }.forEach { choice ->
                    FilterChip(
                        selected = settings.font == choice,
                        onClick = { update { s -> s.copy(font = choice) } },
                        label = { Text(choice.label, fontSize = 12.sp) },
                    )
                }
                FilterChip(
                    selected = settings.font == FontChoice.CUSTOM,
                    onClick = { close(onPickFont) },
                    label = {
                        Text(
                            if (settings.customFontName.isBlank()) "导入字体" else settings.customFontName.take(8),
                            fontSize = 12.sp,
                        )
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置 · 翻页、听书与亮度") }
            LabeledRow("听书定时", if (settings.ttsSleepMinutes == 0) "不定时" else "${settings.ttsSleepMinutes} 分钟")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 60).forEach { minutes -> FilterChip(settings.ttsSleepMinutes == minutes,
                    { update { it.copy(ttsSleepMinutes = minutes) } }, label = { Text(if (minutes == 0) "关闭" else "$minutes 分钟") }) }
            }
            if (advanced) {
            Text("翻页动画", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageAnimation.entries.forEach { animation ->
                    FilterChip(
                        selected = settings.pageAnimation == animation,
                        onClick = { update { s -> s.copy(pageAnimation = animation) } },
                        label = { Text(animation.label, fontSize = 11.sp) },
                    )
                }
            }
            LabeledRow("亮度", if (settings.screenBrightness < 0f) "跟随系统" else "${(settings.screenBrightness * 100).toInt()}%")
            Slider(value = settings.screenBrightness.coerceAtLeast(0f), valueRange = 0f..1f,
                onValueChange = { value -> update { it.copy(screenBrightness = value) } })
            TextButton(onClick = { update { it.copy(screenBrightness = -1f) } }) { Text("跟随系统亮度") }
            LabeledRow("朗读速度", "${settings.ttsRate}×")
            Slider(value = settings.ttsRate, valueRange = 0.5f..2.5f,
                onValueChange = { value -> update { it.copy(ttsRate = value) } })
            }
        }
    }
}

private data class ReadingTypography(val fontSize: Int, val lineSpacingPercent: Int, val paddingDp: Int) {
    fun applyTo(settings: ReaderSettings) = settings.copy(
        fontSize = fontSize, lineSpacingPercent = lineSpacingPercent, paddingDp = paddingDp,
    )
    companion object {
        fun from(settings: ReaderSettings) = ReadingTypography(settings.fontSize, settings.lineSpacingPercent, settings.paddingDp)
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ThemeDot(
    theme: ReaderTheme,
    settings: ReaderSettings,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = when (theme) {
        ReaderTheme.CUSTOM -> settings.customBackground
        else -> theme.background
    }
    val foreground = when (theme) {
        ReaderTheme.CUSTOM -> settings.customForeground
        else -> theme.onBackground
    }
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(background))
                .border(if (selected) 2.dp else 1.dp, ring, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("文", color = Color(foreground), fontSize = 14.sp)
        }
        Text(
            if (theme == ReaderTheme.CUSTOM) "自定义" else theme.label,
            fontSize = 10.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 52.dp),
        )
    }
}

/** 自定义底色/正文色：给一组预设与三条 RGB 滑杆，并提示对比度是否过低。 */
@Composable
private fun CustomColorRow(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val contrast = ReaderSettings.contrast(settings.customBackground, settings.customForeground)
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ChannelSlider("红", ((settings.customBackground shr 16) and 0xFF).toInt()) { value ->
                onUpdate { s -> s.copy(customBackground = withChannel(s.customBackground, 16, value)) }
            }
            ChannelSlider("绿", ((settings.customBackground shr 8) and 0xFF).toInt()) { value ->
                onUpdate { s -> s.copy(customBackground = withChannel(s.customBackground, 8, value)) }
            }
            ChannelSlider("蓝", (settings.customBackground and 0xFF).toInt()) { value ->
                onUpdate { s -> s.copy(customBackground = withChannel(s.customBackground, 0, value)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (contrast < 4.5f) "正文与底色对比度较低（${"%.1f".format(contrast)}:1），建议调深正文或调浅底色。" else "对比度 ${"%.1f".format(contrast)}:1",
            fontSize = 11.sp,
            color = if (contrast < 4.5f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = {
            onUpdate { s ->
                // 按底色亮度自动挑一个深/浅正文色，省得用户自己配。
                val dark = ReaderSettings.luminance(s.customBackground) < 0.5f
                s.copy(customForeground = if (dark) 0xFFCFD6E2 else 0xFF2A2620)
            }
        }) { Text("按底色自动配正文色", fontSize = 12.sp) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.weight(1f)) {
        Text("$label $value", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = 0f..255f, modifier = Modifier.height(28.dp))
    }
}

private fun withChannel(color: Long, shift: Int, value: Int): Long {
    val mask = (0xFFL shl shift).inv()
    val shifted = (value.toLong() and 0xFF) shl shift
    return (color and mask) or shifted or 0xFF000000L
}
