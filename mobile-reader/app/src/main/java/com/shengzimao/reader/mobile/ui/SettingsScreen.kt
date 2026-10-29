package com.shengzimao.reader.mobile.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.BuildConfig
import com.shengzimao.reader.mobile.WelcomeBook
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.FontChoice
import com.shengzimao.reader.mobile.data.PageAnimation
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReaderTheme
import com.shengzimao.reader.mobile.ui.theme.AppDimens
import com.shengzimao.reader.mobile.ui.theme.GlassDefaults
import com.shengzimao.reader.mobile.ui.theme.GlassSurface
import java.time.LocalDate

/** 设置中心：排版、翻页、数据与关于。没有隐蔽/伪装相关设置。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: ReaderViewModel, onBack: (() -> Unit)? = null, embedded: Boolean = false, category: ToolPage = ToolPage.APPEARANCE) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val settings = state.settings
    var confirmClear by remember { mutableStateOf(false) }
    var cacheSheet by remember { mutableStateOf(false) }
    var includeMangaImages by remember { mutableStateOf(false) }
    val manga by viewModel.manga.state.collectAsStateWithLifecycle()
    val mangaSettings by viewModel.mangaSettings.collectAsStateWithLifecycle()
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { viewModel.exportBackup(it, includeMangaImages) } }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it) } }
    val update: ((ReaderSettings) -> ReaderSettings) -> Unit = { transform -> viewModel.updateSettings(transform) }
    if (cacheSheet) MangaCacheSheet(viewModel.manga, manga.cache) { cacheSheet = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (category in setOf(ToolPage.NOVEL, ToolPage.COMIC, ToolPage.SPEECH)) item {
                Text("这里修改全局默认；单本作品的设置可在阅读页内调整。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (category == ToolPage.APPEARANCE) {
            item { SectionTitle("外观风格") }
            item {
                SettingCard {
                    Text(
                        "选择界面的整体视觉语言；只影响界面，不影响阅读正文的底色与字号。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    com.shengzimao.reader.mobile.ui.theme.AppThemeStyle.entries.forEach { style ->
                        val selected = settings.appThemeStyle == style.name
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(style.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                if (selected) {
                                    Text("使用中", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    TextButton(
                                        onClick = { update { s -> s.copy(appThemeStyle = style.name) } },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                                    ) { Text("切换", fontSize = 12.sp) }
                                }
                            }
                            Text(style.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selected) {
                                Spacer(Modifier.height(6.dp))
                                com.shengzimao.reader.mobile.ui.theme.AppStylePreview(style)
                            }
                        }
                    }
                }
            }
            // 玻璃质感与配色只在「液态玻璃」风格下才有意义，其余风格不渲染，避免选了却没效果。
            if (settings.appThemeStyle == com.shengzimao.reader.mobile.ui.theme.AppThemeStyle.LIQUID_GLASS.name) {
                item { SectionTitle("氛围") }
                item {
                    SettingCard {
                        Text(
                            "氛围会同时换掉背景颜色、强调色和背景动效，比单纯换配色更有情绪。选了氛围后下方的「配色方案」不再生效。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        com.shengzimao.reader.mobile.ui.theme.Atmosphere.entries.forEach { item ->
                            val selected = settings.atmosphere == item.name
                            AtmosphereRow(
                                atmosphere = item,
                                selected = selected,
                                dark = com.shengzimao.reader.mobile.ui.theme.LocalGlassDark.current,
                                onClick = { update { s -> s.copy(atmosphere = item.name) } },
                            )
                        }
                    }
                }
                item { SectionTitle("玻璃质感") }
                item {
                    SettingCard {
                        Text(
                            "三种玻璃的「光怎么走」不同：液态最通透、拟态靠凹凸做厚度、磨砂最安静。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        com.shengzimao.reader.mobile.ui.theme.GlassMode.entries.forEach { mode ->
                            OptionRow(
                                title = mode.label,
                                subtitle = mode.description,
                                selected = settings.glassMode == mode.name,
                                onClick = { update { s -> s.copy(glassMode = mode.name) } },
                            )
                        }
                    }
                }
                item { SectionTitle("配色方案") }
                item {
                    SettingCard {
                        Text(
                            "配色决定背景的彩色晕染与选中态颜色；深色外观下会自动换成同一色相的夜色版本。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        com.shengzimao.reader.mobile.ui.theme.GlassPaletteId.entries.forEach { id ->
                            val selected = settings.glassPalette == id.name
                            val swatch = com.shengzimao.reader.mobile.ui.theme.auroraPalette(id, dark = false)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { update { s -> s.copy(glassPalette = id.name) } }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 色板预览：三个主色团并排，直观看出这套配色长什么样。
                                // 「透明」配色的色团本身几乎没有颜色，改用描边空心圈表达「无色」。
                                Row(Modifier.width(70.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val clear = id == com.shengzimao.reader.mobile.ui.theme.GlassPaletteId.CLEAR
                                    swatch.blooms.take(3).forEach { color ->
                                        Box(
                                            Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .then(
                                                    if (clear) {
                                                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                                    } else {
                                                        Modifier.background(color)
                                                    },
                                                ),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(id.label, style = MaterialTheme.typography.titleSmall)
                                    Text(id.hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "使用中",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { SectionTitle("外观深浅") }
            item {
                SettingCard {
                    Text(
                        "默认跟随系统。想固定白色界面就选「白色」，睡前固定深色选「深色」。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "AUTO" to "跟随系统",
                        "LIGHT" to "白色（浅色）",
                        "DARK" to "深色",
                    ).forEach { (key, label) ->
                        OptionRow(
                            title = label,
                            subtitle = null,
                            selected = settings.appearance == key,
                            onClick = { update { s -> s.copy(appearance = key) } },
                        )
                    }
                }
            }
            }
            if (category == ToolPage.NOVEL) {
            item { SectionTitle("排版") }
            item {
                SettingCard {
                    TypographySliderRow(
                        label = "字号",
                        value = settings.fontSize.toFloat(),
                        valueText = { "${it.toInt()} sp" },
                        range = 12f..40f,
                        onChange = { update { s -> s.copy(fontSize = it.toInt()) } },
                    )
                    TypographySliderRow(
                        label = "行距",
                        value = settings.lineSpacingPercent.toFloat(),
                        valueText = { "${(it / 5).toInt() * 5}%" },
                        range = 100f..260f,
                        onChange = { update { s -> s.copy(lineSpacingPercent = (it / 5).toInt() * 5) } },
                    )
                    TypographySliderRow(
                        label = "页边距",
                        value = settings.paddingDp.toFloat(),
                        valueText = { "${it.toInt()} dp" },
                        range = 8f..40f,
                        onChange = { update { s -> s.copy(paddingDp = it.toInt()) } },
                    )
                    Text("字体", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        FontChoice.entries.forEachIndexed { index, choice ->
                            SegmentedButton(
                                selected = settings.font == choice,
                                onClick = { update { s -> s.copy(font = choice) } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = FontChoice.entries.size),
                            ) { Text(choice.label.removeSuffix("（系统）"), fontSize = 12.sp) }
                        }
                    }
                    SwitchRow("正文加粗", settings.fontBold) { value -> update { s -> s.copy(fontBold = value) } }
                    SwitchRow("段落首行缩进", settings.indentParagraphs) { value -> update { s -> s.copy(indentParagraphs = value) } }
                    SwitchRow("行距随字号联动", settings.lineSpacingFollowsFont) { value -> update { s -> s.copy(lineSpacingFollowsFont = value) } }
                }
            }
            item { SectionTitle("阅读主题") }
            item {
                SettingCard {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ReaderTheme.entries.forEach { theme ->
                            ThemeSwatch(theme, settings.theme == theme) { update { s -> s.copy(theme = theme) } }
                        }
                    }
                    if (settings.theme == com.shengzimao.reader.mobile.data.ReaderTheme.CUSTOM) {
                        Text(
                            "自定义配色在阅读页的「阅读设置」面板里调整底色与正文色。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            item { SectionTitle("翻页与交互") }
            item {
                SettingCard {
                    Text("翻页方式", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    // 用 FlowRow：窄屏上选项会换行，而不是被挤成竖排单字。
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        com.shengzimao.reader.mobile.data.TurnMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.turnMode == mode,
                                onClick = { update { s -> s.copy(turnMode = mode) } },
                                label = { Text(mode.label, fontSize = 12.sp) },
                            )
                        }
                    }
                    Text("翻页动画", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            PageAnimation.entries.forEachIndexed { index, animation ->
                                SegmentedButton(
                                    selected = settings.pageAnimation == animation,
                                    onClick = { update { s -> s.copy(pageAnimation = animation) } },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PageAnimation.entries.size),
                                ) { Text(animation.label, fontSize = 11.sp, maxLines = 1) }
                            }
                        }
                    }
                    SwitchRow("音量键翻页", settings.volumeKeysTurnPages) { value -> update { s -> s.copy(volumeKeysTurnPages = value) } }
                    SwitchRow("阅读时保持亮屏（小说）", settings.keepScreenOn) { value -> update { s -> s.copy(keepScreenOn = value) } }
                    SwitchRow("阅读时隐藏状态栏（小说）", settings.fullScreen) { value -> update { s -> s.copy(fullScreen = value) } }
                    SwitchRow("翻页时显示位置提示（小说）", settings.showPageHint) { value -> update { s -> s.copy(showPageHint = value) } }
                    SwitchRow("显示页脚（章节与页码）", settings.showFooter) { value -> update { s -> s.copy(showFooter = value) } }
                }
            }
            item { SectionTitle("页码与夜间") }
            item {
                SettingCard {
                    Text("页码样式", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        com.shengzimao.reader.mobile.data.PageIndicator.entries.forEach { indicator ->
                            FilterChip(
                                selected = settings.pageIndicator == indicator,
                                onClick = { update { s -> s.copy(pageIndicator = indicator) } },
                                label = { Text(indicator.label, fontSize = 12.sp) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("夜间模式（固定深色外观）", settings.nightMode) { value -> update { s -> s.copy(nightMode = value) } }
                    Text(
                        "应用内亮度在阅读页左右边缘拖动调节，或在此固定：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("亮度", fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (settings.screenBrightness < 0f) "跟随系统" else "${(settings.screenBrightness * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = if (settings.screenBrightness < 0f) 0.5f else settings.screenBrightness,
                        onValueChange = { update { s -> s.copy(screenBrightness = it) } },
                        valueRange = 0.02f..1f,
                    )
                    TextButton(onClick = { update { s -> s.copy(screenBrightness = -1f) } }) { Text("恢复跟随系统", fontSize = 12.sp) }
                }
            }
            }
            if (category == ToolPage.COMIC) {
            item { SectionTitle("漫画阅读") }
            item {
                SettingCard {
                    Text(
                        "漫画有独立的阅读偏好（与小说分开保存）。这里的改动是「全局默认」，单本漫画仍可在阅读页里单独调整。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("翻页方式", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.shengzimao.reader.mobile.data.MangaReadingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mangaSettings.mode == mode,
                                onClick = { viewModel.updateMangaSettings { it.copy(mode = mode) } },
                                label = { Text(mode.label, fontSize = 12.sp) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("阅读底色", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.shengzimao.reader.mobile.data.MangaBackground.entries.forEach { background ->
                            FilterChip(
                                selected = mangaSettings.background == background,
                                onClick = { viewModel.updateMangaSettings { it.copy(background = background) } },
                                label = { Text(background.label, fontSize = 12.sp) },
                            )
                        }
                    }
                    SwitchRow("阅读时保持亮屏（漫画）", mangaSettings.keepScreenOn) { v ->
                        viewModel.updateMangaSettings { it.copy(keepScreenOn = v) }
                    }
                    SwitchRow("全屏沉浸（漫画）", mangaSettings.immersive) { v ->
                        viewModel.updateMangaSettings { it.copy(immersive = v) }
                    }
                    SwitchRow("翻页时显示位置提示（漫画）", mangaSettings.showPageHint) { v ->
                        viewModel.updateMangaSettings { it.copy(showPageHint = v) }
                    }
                    SwitchRow("到达章末自动加载下一章（仅条漫）", mangaSettings.autoNextChapter) { v ->
                        viewModel.updateMangaSettings { it.copy(autoNextChapter = v) }
                    }
                    SwitchRow("双击放大", mangaSettings.doubleTapZoom) { v ->
                        viewModel.updateMangaSettings { it.copy(doubleTapZoom = v) }
                    }
                    SliderRow(
                        label = "预取下一章张数",
                        value = mangaSettings.prefetchPages.toFloat(),
                        valueText = "${mangaSettings.prefetchPages} 张",
                        range = 0f..6f,
                        onChange = { v -> viewModel.updateMangaSettings { it.copy(prefetchPages = v.toInt().coerceIn(0, 6)) } },
                    )
                }
            }
            }
            if (category == ToolPage.DATA) {
            item { SectionTitle("阅读统计") }
            item { ReadingStats(state.books) }
            }
            if (category == ToolPage.SPEECH) {
            item { SectionTitle("朗读") }
            item {
                SettingCard {
                    SwitchRow("朗读自动翻页", settings.autoTtsTurnPage) { value -> update { s -> s.copy(autoTtsTurnPage = value) } }
                    SwitchRow("朗读时高亮当前段", settings.ttsHighlight) { value -> update { s -> s.copy(ttsHighlight = value) } }
                    SliderRow(
                        label = "语速",
                        value = settings.ttsRate,
                        valueText = "${"%.1f".format(settings.ttsRate)}×",
                        range = 0.5f..2.5f,
                        onChange = { update { s -> s.copy(ttsRate = it) } },
                    )
                    SliderRow(
                        label = "音调",
                        value = settings.ttsPitch,
                        valueText = "${"%.1f".format(settings.ttsPitch)}",
                        range = 0.5f..2f,
                        onChange = { update { s -> s.copy(ttsPitch = it) } },
                    )
                }
            }
            }
            if (category == ToolPage.DOWNLOAD_SETTINGS) {
            item { SectionTitle("漫画存储") }
            item { SettingCard {
                Text("临时图片 ${manga.cache.readable()} · 主动下载 ${manga.cache.readable(manga.cache.downloadedBytes)}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewModel.manga.refreshCache(); cacheSheet = true }) { Text("管理漫画缓存与占用") }
                Text("清理临时缓存保留主动下载、本地导入作品与阅读进度。", style = MaterialTheme.typography.bodySmall)
            } }
            item { SectionTitle("在线下载") }
            item {
                SettingCard {
                    SwitchRow("新任务仅在 Wi-Fi 下下载", settings.downloadWifiOnly) { value -> update { it.copy(downloadWifiOnly = value) } }
                    SwitchRow("重开应用后继续中断任务", settings.downloadAutoResume) { value ->
                        update { it.copy(downloadAutoResume = value) }
                        if (value) viewModel.downloads.resumeInterrupted()
                    }
                    Text("暂停和取消的任务不会自动启动。阅读漫画时，后台图片下载会降低并发。", style = MaterialTheme.typography.bodySmall)
                    SliderRow(
                        label = "同时下载章节数",
                        value = settings.downloadConcurrency.toFloat(),
                        valueText = "${settings.downloadConcurrency} 路",
                        range = 1f..8f,
                        onChange = { update { s -> s.copy(downloadConcurrency = it.toInt().coerceIn(1, 8)) } },
                    )
                    Text(
                        "聚合搜书与番茄小说都按此并发读取正文。调高更快，但更容易被源站限速；" +
                            "如果频繁失败，请调回 3–4 路。默认 4 路。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
            if (category == ToolPage.NOVEL) {
            item { SectionTitle("追更") }
            item {
                SettingCard {
                    SwitchRow("每 30 分钟自动检查追更（仅应用运行时）", state.autoUpdate) { viewModel.setAutoUpdate(it) }
                }
            }
            }
            if (category == ToolPage.DATA) {
            item { SectionTitle("数据") }
            item {
                SettingCard {
                    Text(
                        "书库索引、正文与设置都保存在应用私有目录，不会上传。卸载应用会清除这些数据。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportLauncher.launch("szm-backup-${java.time.LocalDate.now()}.szmbak") }) { Text("导出阅读备份") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("恢复阅读备份") }
                    }
                    SwitchRow("包含已下载及本地漫画图片", includeMangaImages) { includeMangaImages = it }
                    Text("漫画图片约 ${manga.cache.readable(manga.cache.downloadedBytes + manga.cache.localBytes)}；默认仅保存目录、位置、页面书签、设置及去除凭据字段的源。临时缓存不导出。未包含图片的本地漫画需重新导入原文件。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmClear = true }) { Text("清空阅读历史") }
                }
            }
            }
            if (category == ToolPage.DATA) {
            item { SectionTitle("关于") }
            item {
                SettingCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Text("猫", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("圣子猫阅读", fontWeight = FontWeight.SemiBold)
                            Text(
                                "版本 ${BuildConfig.VERSION_NAME} · 手机版",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "手机版专注普通阅读，不含隐蔽 / 伪装模式。阅读进度、书签与统计仅保存在本机；在线搜书包含聚合搜书与番茄小说两个页面。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空阅读历史？") },
            text = { Text("只清空阅读时间与顺序标记，书籍、进度和书签都会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearReadingHistory()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ReadingStats(books: List<Book>) {
    val today = LocalDate.now().toString()
    val todaySeconds = books.sumOf { it.readingDailySeconds[today] ?: 0L }
    val weekSeconds = (0 until 7).sumOf { offset ->
        val day = LocalDate.now().minusDays(offset.toLong()).toString()
        books.sumOf { it.readingDailySeconds[day] ?: 0L }
    }
    val totalSeconds = books.sumOf { it.readingSeconds }
    SettingCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell(minutes(todaySeconds), "今日阅读", Modifier.weight(1f))
            StatCell(minutes(weekSeconds), "近七天", Modifier.weight(1f))
            StatCell(minutes(totalSeconds), "累计", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        val reading = books.count { it.position > 0 && !it.isDone }
        Text(
            "书库 ${books.size} 本 · 在读 $reading 本 · 读完 ${books.count { it.isDone }} 本",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun minutes(seconds: Long): String = when {
    seconds < 60 -> "0 分"
    seconds < 3600 -> "${seconds / 60} 分"
    else -> String.format(java.util.Locale.CHINA, "%.1f 时", seconds / 3600.0)
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    val tokens = com.shengzimao.reader.mobile.ui.theme.LocalAppTokens.current
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(tokens.radii.card),
        color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(tokens.spacing.card)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}

/**
 * 氛围选择行。
 *
 * 左边是该氛围的**真实预览**：用氛围自己的色板画一条渐变，再叠几颗它自己的粒子，
 * 所以选之前就能看出来「雪落」和「雨夜」到底差在哪，而不是只有一行文字。
 */
@Composable
private fun AtmosphereRow(
    atmosphere: com.shengzimao.reader.mobile.ui.theme.Atmosphere,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val palette = remember(atmosphere, dark) { atmosphere.palette(dark) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    // 没有氛围时用主题色板兜底，保证这一格不会是空白。
                    palette?.let { p ->
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(
                                p.base,
                                p.blooms.getOrElse(1) { p.base },
                                p.accent.copy(alpha = 0.42f),
                            ),
                        )
                    } ?: androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ),
                )
                .then(
                    if (selected) {
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    },
                ),
        ) {
            // 用氛围自己的颜色点几颗示意粒子，直观提示「这个氛围会动」。
            if (palette != null) {
                Box(Modifier.fillMaxSize().padding(6.dp)) {
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .align(Alignment.TopStart)
                            .background(palette.accent.copy(alpha = 0.9f)),
                    )
                    Box(
                        Modifier
                            .size(2.dp)
                            .clip(CircleShape)
                            .align(Alignment.Center)
                            .background(palette.accentAlt.copy(alpha = 0.85f)),
                    )
                    Box(
                        Modifier
                            .size(2.dp)
                            .clip(CircleShape)
                            .align(Alignment.BottomEnd)
                            .background(palette.accent.copy(alpha = 0.7f)),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                atmosphere.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                atmosphere.hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "使用中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 单选行：标题 + 可选说明 + 右侧勾选。玻璃卡片里比 RadioButton 更轻。 */
@Composable
private fun OptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "使用中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TypographySliderRow(
    label: String,
    value: Float,
    valueText: (Float) -> String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val displayed = draft ?: value
    LaunchedEffect(value) { if (draft == value) draft = null }
    val commit by rememberUpdatedState {
        draft?.let { next ->
            draft = null
            if (valueText(next) != valueText(value)) onChange(next)
        }
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(valueText(displayed), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = displayed, onValueChange = { draft = it },
            onValueChangeFinished = { commit() }, valueRange = range)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(valueText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(theme.background))
                // 色块本身可点：用户很自然地去点色块，而不是下面那行小字。
                .clickable(enabled = !selected, onClick = onClick)
                .then(
                    if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                .semantics { this.selected = selected },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.Check,
                    contentDescription = "已选",
                    tint = Color(theme.onBackground),
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text("文", color = Color(theme.onBackground), fontSize = 15.sp)
            }
        }
        Text(
            theme.label,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            if (selected) "使用中" else "点击选择",
            fontSize = 10.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun sortLabel(key: String): String = when (key) {
    "recent" -> "最近阅读"
    "added" -> "导入时间"
    "title" -> "书名"
    "progress" -> "阅读进度"
    else -> key
}

/** 关于页：版本、数据说明与内置欢迎书入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: ReaderViewModel, navigator: Navigator) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val welcome = state.books.firstOrNull { it.id == WelcomeBook.ID }
    val scroll = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = { navigator.closeAbout() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(
                                    com.shengzimao.reader.mobile.ui.theme.LocalAurora.current.accent,
                                    com.shengzimao.reader.mobile.ui.theme.LocalAurora.current.accentAlt,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) { Text("猫", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("圣子猫阅读", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("手机版 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            SettingCard {
                InfoLine("阅读模式", "普通全屏阅读（不含隐蔽模式）")
                InfoLine("支持格式", "小说：TXT（自动识别编码）、EPUB；漫画：CBZ、ZIP、图片。")
                InfoLine("数据位置", "应用私有目录，卸载即清除")
                InfoLine("在线搜书", "聚合搜书（SoNovel）、番茄小说")
                InfoLine("下载管理", "小说与漫画统一队列，支持进度、取消、重试和历史。仅在应用运行时处理；进程退出后未完成任务需手动重试，漫画复用已缓存图片。")
                InfoLine("在线搜漫", "由用户导入声明式或旧式漫画源，不内置漫画内容。支持受限规则脚本，不能访问 JVM、Android 或手机文件；不支持私有解密及 Mihon / Tachiyomi APK 扩展。源配置导入成功不代表源站当前可读。")
                InfoLine("漫画与数据", "图片、封面及漫画版权归相应权利人；源规则不代表作品授权。第三方站点可能接收搜索关键词、IP 及请求信息。阅读备份包含设置、漫库目录、进度、书签及漫画源，图片可以选择导出。")
                InfoLine("开源许可", "AGPL-3.0；保留第三方组件各自许可，详见源码 README 与声明。")
                InfoLine("在线听书", "可选微软 Read Aloud 在线声线。启用后当前朗读及下一句预读文本直接发送至微软，需联网；也可使用系统声音。语音令牌算法参考 edge-tts，LGPL-3.0，许可随包附带。")
                InfoLine("番茄说明", "独立适配公开接口，与番茄官方无隶属或合作关系。仅还原已识别的公开网页字体；未知字体、缺字或登录、付费、验证要求会停止。")
                InfoLine("内容与免责", "作品版权归各权利人。请仅使用有权访问的内容并遵守平台条款；不保证第三方内容、接口或下载持续可用。")
                InfoLine("致谢", "SoNovel、Tomato-Novel-Downloader（MIT）、ying-ck/fanqienovel-downloader 字符数据（AGPL-3.0）、Rhino、AndroidX、OkHttp、Okio、jsoup（MIT）等开源项目。")
            }
            Spacer(Modifier.height(16.dp))
            if (welcome != null) {
                OutlinedButton(onClick = { navigator.closeAbout(); navigator.openReader(welcome.id) }) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("重新阅读使用说明", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
