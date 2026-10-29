package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.reader.OnlineSpeechVoices

@Composable
internal fun OnlineSpeechVoiceSection(selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("online_speech", 0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pendingConsent by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = OnlineSpeechVoices.find(selected)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("在线音色", style = MaterialTheme.typography.titleSmall)
            Text(chosen?.let { "当前：${it.name} · ${it.description}" } ?: "六个独立声线 · 女声 / 男声",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "选择") }
    }
    if (expanded) {
        Text("由微软 Read Aloud 在线合成，需要联网；声线与下方调音风格分别选择。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OnlineSpeechVoices.all.forEach { voice ->
            val checked = selected == voice.id
            Surface(color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                    if (preferences.getBoolean("consent_v1", false)) onSelect(voice.id) else pendingConsent = voice.id
                }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(checked, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(voice.name, style = MaterialTheme.typography.bodyMedium)
                        Text(voice.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("在线", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (chosen != null) TextButton(onClick = { onSelect("") }) { Text("切回系统默认声音") }
        Text("选好后可用面板底部的“试听声音”试听。网络不可用时可切回已安装的系统声音。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    pendingConsent?.let { id ->
        AlertDialog(onDismissRequest = { pendingConsent = null },
            title = { Text("使用在线音色") },
            text = { Text("朗读和试听时，当前句子及下一句预读文本会直接发送给微软语音服务，并在手机本地短期缓存音频。需要网络，服务可用性会随网络或接口变化。书库文件不会整本上传。") },
            confirmButton = { TextButton(onClick = {
                preferences.edit().putBoolean("consent_v1", true).apply()
                pendingConsent = null; onSelect(id)
            }) { Text("同意并选择") } },
            dismissButton = { TextButton(onClick = { pendingConsent = null }) { Text("取消") } })
    }
}
