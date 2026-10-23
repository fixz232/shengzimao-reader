package com.shengzimao.reader.mobile.ui

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.time.LocalDate

/** 设置中心：排版、翻页、数据与关于。没有隐蔽/伪装相关设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ReaderViewModel, onBack: (() -> Unit)? = null, embedded: Boolean = false) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val settings = state.settings
    var confirmClear by remember { mutableStateOf(false) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it) } }
    val update: ((ReaderSettings) -> ReaderSettings) -> Unit = { transform -> viewModel.updateSettings(transform) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("排版") }
            item {
                SettingCard {
                    SliderRow(
                        label = "字号",
                        value = settings.fontSize.toFloat(),
                        valueText = "${settings.fontSize} sp",
                        range = 12f..40f,
                        onChange = { update { s -> s.copy(fontSize = it.toInt()) } },
                    )
                    SliderRow(
                        label = "行距",
                        value = settings.lineSpacingPercent.toFloat(),
                        valueText = "${settings.lineSpacingPercent}%",
                        range = 100f..260f,
                        onChange = { update { s -> s.copy(lineSpacingPercent = (it / 5).toInt() * 5) } },
                    )
                    SliderRow(
                        label = "页边距",
                        value = settings.paddingDp.toFloat(),
                        valueText = "${settings.paddingDp} dp",
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
                }
            }
            item { SectionTitle("阅读主题") }
            item {
                SettingCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        ReaderTheme.entries.forEach { theme ->
                            ThemeSwatch(theme, settings.theme == theme) { update { s -> s.copy(theme = theme) } }
                        }
                    }
                }
            }
            item { SectionTitle("翻页与交互") }
            item {
                SettingCard {
                    Text("翻页动画", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    SwitchRow("阅读时保持亮屏", settings.keepScreenOn) { value -> update { s -> s.copy(keepScreenOn = value) } }
                    SwitchRow("阅读时隐藏状态栏", settings.fullScreen) { value -> update { s -> s.copy(fullScreen = value) } }
                }
            }
            item { SectionTitle("书库") }
            item {
                SettingCard {
                    SwitchRow("网格显示", settings.gridView) { value -> update { s -> s.copy(gridView = value) } }
                    Text(
                        "排序：${sortLabel(settings.sort)}（在书库右上角切换）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item { SectionTitle("阅读统计") }
            item { ReadingStats(state.books) }
            item { SectionTitle("追更") }
            item {
                SettingCard {
                    SwitchRow("每 30 分钟自动检查追更（仅应用运行时）", state.autoUpdate) { viewModel.setAutoUpdate(it) }
                    SwitchRow("朗读自动翻页", settings.autoTtsTurnPage) { value -> update { s -> s.copy(autoTtsTurnPage = value) } }
                    Text("朗读（TTS）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
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
            item { SectionTitle("数据") }
            item {
                SettingCard {
                    Text(
                        "书库索引、正文与设置都保存在应用私有目录，不会上传。卸载应用会清除这些数据。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportLauncher.launch("szm-backup-${java.time.LocalDate.now()}.szmbak") }) { Text("导出备份") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("恢复备份") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmClear = true }) { Text("清空阅读历史") }
                }
            }
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
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
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
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(theme.background))
                .padding(1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("文", color = Color(theme.onBackground), fontSize = 15.sp)
        }
        Text(
            theme.label,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = onClick, enabled = true) { Text(if (selected) "使用中" else "选择", fontSize = 11.sp) }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("猫", color = MaterialTheme.colorScheme.onPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("圣子猫阅读", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("手机版 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            SettingCard {
                InfoLine("阅读模式", "普通全屏阅读（不含隐蔽模式）")
                InfoLine("支持格式", "TXT（自动识别编码）、EPUB")
                InfoLine("数据位置", "应用私有目录，卸载即清除")
                InfoLine("在线搜书", "聚合搜书（SoNovel）、番茄小说")
                InfoLine("开源许可", "AGPL-3.0；保留第三方组件各自许可，详见源码 README 与声明。")
                InfoLine("番茄说明", "独立适配公开接口，与番茄官方无隶属或合作关系。不绕过登录、付费、验证码或字体加密。")
                InfoLine("内容与免责", "作品版权归各权利人。请仅使用有权访问的内容并遵守平台条款；不保证第三方内容、接口或下载持续可用。")
                InfoLine("致谢", "SoNovel、Tomato-Novel-Downloader（MIT）、Rhino、AndroidX、OkHttp、Okio 等开源项目。")
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
