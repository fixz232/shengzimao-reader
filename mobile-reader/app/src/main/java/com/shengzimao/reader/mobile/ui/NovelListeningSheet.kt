package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.reader.ReaderTts
import com.shengzimao.reader.mobile.reader.OnlineSpeechVoices

/**
 * 听书面板。
 *
 * 本轮改动：
 * 1. 语速从「5 个固定档」改成**连续滑杆**（0.5×~2.5×，步进 0.05），
 *    常用区间 0.9~1.3 之间终于能微调了；
 * 2. 新增**音色选择**：列出系统引擎的所有中文音色，可现场切换；
 * 3. 定时停止显示**剩余时间**，并且暂停时不再空转计时。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NovelListeningSheet(
    settings: ReaderSettings,
    speech: ReaderTts.State,
    active: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onSelectVoice: (String) -> Unit,
    voices: List<android.speech.tts.Voice>,
    sleepRemainingMs: Long,
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onReconnect: () -> Unit,
    bookTitle: String = "",
    chapterTitle: String = "",
) {
    val context = LocalContext.current
    var showVoices by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var draftRate by remember(settings.ttsRate) { mutableFloatStateOf(settings.ttsRate) }
    var draftPitch by remember(settings.ttsPitch) { mutableFloatStateOf(settings.ttsPitch) }
    val stopPreview by rememberUpdatedState(onStopPreview)
    DisposableEffect(Unit) { onDispose { stopPreview() } }

    AppModalSheet(onDismissRequest = { onStopPreview(); onDismiss() },
        title = if (showSettings) "声音与播放设置" else "听书", actions = {
            TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "返回播放器" else "声音设置") }
        }) {
        Column(Modifier.fillMaxWidth().height(modalPageHeight()).navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showSettings) {
                    Text("选择真实声线，再按喜好调整语速与音高。", style = MaterialTheme.typography.bodySmall)
            OnlineSpeechVoiceSection(speech.voiceName, onSelectVoice)
            HorizontalDivider()
            SpeechProfileSection(settings, onUpdate)

            // ---------- 语速：连续可调 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语速", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${"%.2f".format(draftRate)}×",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = draftRate,
                onValueChange = { raw ->
                    // 按 0.05 取整：拖动时不会产生 1.0372 这种噪声值。
                    val snapped = (raw * 20).toInt() / 20f
                    draftRate = snapped.coerceIn(0.5f, 2.5f)
                },
                onValueChangeFinished = { onUpdate { it.copy(ttsRate = draftRate, ttsProfile = "") } },
                valueRange = 0.5f..2.5f,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.7f, 0.85f, 1f, 1.15f, 1.3f, 1.6f, 2f).forEach { value ->
                    FilterChip(
                        selected = kotlin.math.abs(settings.ttsRate - value) < 0.001f,
                        onClick = { onUpdate { it.copy(ttsRate = value, ttsProfile = "") } },
                        label = { Text("${value}×") },
                    )
                }
            }

            // ---------- 音高 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音高", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text("${"%.2f".format(draftPitch)}", style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = draftPitch,
                onValueChange = { raw ->
                    val snapped = (raw * 20).toInt() / 20f
                    draftPitch = snapped.coerceIn(0.5f, 2f)
                },
                onValueChangeFinished = { onUpdate { it.copy(ttsPitch = draftPitch, ttsProfile = "") } },
                valueRange = 0.5f..2f,
            )

            // ---------- 音色 ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showVoices = !showVoices }
                    .padding(vertical = 6.dp),
            ) {
                Text("系统基础声线", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    OnlineSpeechVoices.find(speech.voiceName)?.let { "当前使用在线${it.name}" }
                        ?: if (speech.voiceName.isBlank()) "系统默认" else speech.voiceName.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (showVoices) "收起" else "选择", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (showVoices) {
                val options = remember(voices) { listOf(null) + voices }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { voice ->
                        val name = voice?.name ?: ""
                        val selected = if (name.isBlank()) speech.voiceName.isBlank() else speech.voiceName == name
                        val installed = voice?.features.orEmpty().contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED).not()
                        val label = voice?.let {
                            "${it.locale.displayName} · ${it.name.substringAfterLast('/')} · " +
                                if (!installed) "待安装" else if (it.isNetworkConnectionRequired) "需联网" else "离线可用"
                        } ?: "系统默认"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = installed) { onSelectVoice(name) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "使用中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    if (voices.isEmpty()) {
                        Text(
                            "当前引擎没有报告可用音色，可能只支持默认语音。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------- 定时停止 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("定时停止", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (sleepRemainingMs > 0) {
                    val minutes = sleepRemainingMs / 60_000
                    val seconds = (sleepRemainingMs % 60_000) / 1000
                    Text(
                        "剩余 %d:%02d".format(minutes, seconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 60).forEach { value ->
                    FilterChip(
                        selected = settings.ttsSleepMinutes == value,
                        onClick = { onUpdate { it.copy(ttsSleepMinutes = value) } },
                        label = { Text(if (value == 0) "不定时" else "$value 分钟") },
                    )
                }
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("自动接续下一页 / 章", modifier = Modifier.weight(1f).padding(top = 12.dp))
                Switch(
                    checked = settings.autoTtsTurnPage,
                    onCheckedChange = { value -> onUpdate { it.copy(autoTtsTurnPage = value) } },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("正文跟读高亮", modifier = Modifier.weight(1f).padding(top = 12.dp))
                Switch(settings.ttsHighlight, { value -> onUpdate { it.copy(ttsHighlight = value) } })
            }

            Text(
                "可选择系统声音或在线音色，锁屏或切到其他应用也会继续；" +
                    "通知栏可以暂停、继续或停止。在线声音需联网，并预读下一句。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                val intent = Intent("com.android.settings.TTS_SETTINGS")
                runCatching { context.startActivity(intent) }.recoverCatching {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }) { Text("打开系统语音设置") }
            TextButton(onClick = onReconnect, enabled = !speech.activeSession && !speech.initializing) { Text("刷新系统引擎与声线") }
                } else {
            if (bookTitle.isNotBlank()) Text(bookTitle, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (chapterTitle.isNotBlank() && chapterTitle != bookTitle) Text(chapterTitle,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(OnlineSpeechVoices.find(speech.voiceName)?.let { "${it.name} · 在线音色" } ?: "系统声音",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (speech.currentText.isNotBlank()) Text(speech.currentText,
                style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)

            Text(
                when {
                    speech.error != null -> speech.error
                    speech.initializing -> "正在连接系统语音引擎…"
                    speech.buffering -> if (speech.previewing) "正在加载试听声音…" else "正在加载在线语音，可暂停或切换声线…"
                    speech.waitingForContent -> "正在加载下一段内容…"
                    speech.paused -> "已暂停，继续时从当前这一句接着读"
                    speech.speaking ->
                        "正在朗读 · 第 ${speech.sentenceIndex + 1}/${speech.sentenceCount} 句"
                    else -> "从当前阅读位置开始，自动接续后面的内容"
                },
                color = if (speech.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            // 整批朗读进度：句级刻度，比只有文字更直观。
            if (speech.sentenceCount > 1 && speech.error == null) {
                val progress = if (speech.sentenceCount <= 1) 0f
                else (speech.sentenceIndex.coerceAtLeast(0)).toFloat() / (speech.sentenceCount - 1)
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onToggle, enabled = !speech.initializing, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (speech.speaking && !speech.paused) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (speech.paused) "继续听书"
                        else if (active && speech.speaking) "暂停听书"
                        else "开始听书",
                    )
                }
                OutlinedButton(onClick = onStop, enabled = active || speech.initializing) {
                    Icon(Icons.Filled.StopCircle, contentDescription = "停止听书", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPrevious,
                    enabled = speech.activeSession && speech.sentenceIndex > 0) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("上一句")
                }
                OutlinedButton(onClick = onNext,
                    enabled = speech.activeSession && speech.sentenceIndex in 0 until speech.sentenceCount - 1) {
                    Text("下一句")
                    Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                TextButton(onClick = { if (speech.previewing) onStopPreview() else onPreview() },
                    enabled = speech.ready && !speech.speaking && !speech.waitingForContent) {
                    Text(if (speech.previewing) "停止试听" else "试听声音")
                }
            }
            if (speech.voiceNotice != null) Text(speech.voiceNotice, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
