package com.shengzimao.reader.mobile.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shengzimao.reader.mobile.data.MangaBackground
import com.shengzimao.reader.mobile.data.MangaReadingMode
import com.shengzimao.reader.mobile.data.MangaScaleMode
import com.shengzimao.reader.mobile.data.MangaSettings

/**
 * 阅读页的窗口效果：沉浸式全屏、保持亮屏、应用内亮度与系统栏图标明暗。
 * 抽成独立 Effect 供小说与漫画阅读页共用，避免两处逻辑各写一遍。
 */
@Composable
fun ReaderWindowEffects(
    enabled: Boolean,
    keepScreenOn: Boolean,
    brightness: Float,
    fullScreen: Boolean,
    dark: Boolean,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val activity = view.context as? Activity
    val window = activity?.window

    DisposableEffect(enabled, fullScreen, dark) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            ?: return@DisposableEffect onDispose { }
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        if (enabled && fullScreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }

    DisposableEffect(enabled, keepScreenOn) {
        if (window != null && enabled && keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { /* 由离开阅读页时的下一次效果或 MainActivity 统一恢复。 */ }
    }

    // 亮度：只按「是否启用」重启，不把 brightness 放进 key。
    // 否则在设置面板里拖动亮度滑杆会让 effect 反复重建，onDispose 先把亮度写回旧值，
    // 视觉上是「拖一下闪一下」，退出时还会把用户中途用系统面板改过的亮度覆盖回初始快照。
    DisposableEffect(enabled, window) {
        if (window == null) return@DisposableEffect onDispose { }
        onDispose {
            val current = window.attributes
            // 复位成「跟随系统」，而不是写回进入时的快照。
            current.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            runCatching { window.attributes = current }
        }
    }
    LaunchedEffect(enabled, brightness, window) {
        if (window == null) return@LaunchedEffect
        val params = window.attributes
        params.screenBrightness = when {
            !enabled -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            brightness < 0f -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> brightness.coerceIn(0.02f, 1f)
        }
        runCatching { window.attributes = params }
    }
}

/**
 * 音量键翻页。挂在阅读页根容器上并请求焦点，
 * 只有获得焦点时才拦截，避免影响其它页面与弹窗。
 */
@Composable
fun Modifier.volumeKeyPager(enabled: Boolean, onPrev: () -> Unit, onNext: () -> Unit): Modifier {
    if (!enabled) return this
    val prev by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
            when (event.key) {
                Key.VolumeUp -> { if (event.type == KeyEventType.KeyDown) prev(); true }
                Key.VolumeDown -> { if (event.type == KeyEventType.KeyDown) next(); true }
                else -> false
            }
        }
}

/** 漫画阅读设置。只影响漫画，不干扰小说排版设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaSettingsSheet(
    settings: MangaSettings,
    onChange: ((MangaSettings) -> MangaSettings) -> Unit,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    var advanced by remember { mutableStateOf(false) }
    AppModalSheet(onDismissRequest = onDismiss, title = "当前漫画设置") {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                onReset?.let { TextButton(onClick = it) { Text("恢复跟随全局设置") } } }

            item {
                SectionTitle("阅读方向")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MangaReadingMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.mode == mode,
                            onClick = { onChange { it.copy(mode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, MangaReadingMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
            }

            item {
                SectionTitle("图片缩放")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MangaScaleMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.scaleMode == mode,
                            onClick = { onChange { it.copy(scaleMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, MangaScaleMode.entries.size),
                        ) { Text(mode.label, fontSize = 12.sp) }
                    }
                }
            }

            item {
                SectionTitle("阅读底色")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaBackground.entries.forEach { background ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(background.color))
                                    .border(
                                        width = if (settings.background == background) 2.dp else 1.dp,
                                        color = if (settings.background == background) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable { onChange { it.copy(background = background) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (settings.background == background) {
                                    Icon(
                                        Icons.Filled.Check, "已选", Modifier.size(20.dp),
                                        tint = if (background.isDark) Color.White else Color.Black,
                                    )
                                }
                            }
                            Text(background.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            item {
                SectionTitle("图片间距 · ${settings.gapDp} dp")
                Slider(
                    value = settings.gapDp.toFloat(),
                    onValueChange = { onChange { s -> s.copy(gapDp = it.toInt()) } },
                    valueRange = 0f..48f,
                    steps = 11,
                )
            }

            item {
                SectionTitle("页面边距 · ${settings.paddingDp} dp")
                Slider(
                    value = settings.paddingDp.toFloat(),
                    onValueChange = { onChange { s -> s.copy(paddingDp = it.toInt()) } },
                    valueRange = 0f..48f,
                    steps = 11,
                )
            }

            item {
                SectionTitle("亮度")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = if (settings.brightness < 0f) 0.5f else settings.brightness,
                        onValueChange = { onChange { s -> s.copy(brightness = it) } },
                        modifier = Modifier.weight(1f),
                        valueRange = 0.02f..1f,
                    )
                    Text(
                        if (settings.brightness < 0f) "跟随系统" else "${(settings.brightness * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (settings.brightness >= 0f) TextButton(onClick = { onChange { it.copy(brightness = -1f) } }) { Text("恢复跟随系统", fontSize = 12.sp) }
            }

            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") } }
            item { MangaSwitchRow("底部章节、时间与电量", settings.showStatusBar) { v -> onChange { it.copy(showStatusBar = v) } } }
            if (advanced) {
            item { MangaSwitchRow("双页跨页（横向 / 日漫）", settings.doublePage) { v -> onChange { it.copy(doublePage = v) } } }
            item { MangaSwitchRow("智能跨页（封面、横向跨页单独显示）", settings.smartSpreads) { v -> onChange { it.copy(smartSpreads = v) } } }
            item { MangaSwitchRow("智能裁白边（保守识别，可随时关闭）", settings.autoCrop) { v -> onChange { it.copy(autoCrop = v) } } }
            item { SectionTitle("左右裁白边 · 每侧 ${settings.cropPercent}%")
                Slider(settings.cropPercent.toFloat(), { value -> onChange { it.copy(cropPercent = value.toInt()) } }, valueRange = 0f..15f) }
            item { MangaSwitchRow("仅非计费网络预取下一章", settings.prefetchWifiOnly) { v -> onChange { it.copy(prefetchWifiOnly = v) } } }
            item { MangaSwitchRow("阅读时保持亮屏", settings.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } } }
            item { MangaSwitchRow("沉浸式阅读（单击中央呼出工具栏）", settings.immersive) { v -> onChange { it.copy(immersive = v) } } }
            item { MangaSwitchRow("双击放大 / 复原", settings.doubleTapZoom) { v -> onChange { it.copy(doubleTapZoom = v) } } }
            item { MangaSwitchRow("音量键翻页", settings.volumeKeysTurnPages) { v -> onChange { it.copy(volumeKeysTurnPages = v) } } }
            item { MangaSwitchRow("缩放时锁定平移（防误触）", settings.lockPan) { v -> onChange { it.copy(lockPan = v) } } }
            item { MangaSwitchRow("到达章末自动加载下一章", settings.autoNextChapter) { v -> onChange { it.copy(autoNextChapter = v) } } }
            item { MangaSwitchRow("显示翻页位置提示", settings.showPageHint) { v -> onChange { it.copy(showPageHint = v) } } }

            item {
                SectionTitle("双击放大倍数 · ${"%.1f".format(settings.doubleTapScale)}×")
                Slider(
                    value = settings.doubleTapScale,
                    onValueChange = { onChange { s -> s.copy(doubleTapScale = it) } },
                    valueRange = 1.5f..4f,
                )
            }

            item {
                SectionTitle("预取下一章图片 · ${settings.prefetchPages} 张")
                Slider(
                    value = settings.prefetchPages.toFloat(),
                    onValueChange = { onChange { s -> s.copy(prefetchPages = it.toInt()) } },
                    valueRange = 0f..6f,
                    steps = 5,
                )
                Text(
                    "预取会在接近章末时提前下载下一章的图片地址与前几张图，翻章更快；设为 0 表示关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun MangaSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
