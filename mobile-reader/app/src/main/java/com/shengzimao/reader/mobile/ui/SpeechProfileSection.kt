package com.shengzimao.reader.mobile.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.reader.SpeechProfile
import com.shengzimao.reader.mobile.reader.SpeechProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeechProfileSection(settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("reader-tts-profiles", Context.MODE_PRIVATE) }
    var custom by remember { mutableStateOf(emptyList<SpeechProfile>()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("女声风格") }
    var editor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(preferences) {
        try {
            custom = withContext(Dispatchers.IO) { SpeechProfiles.decode(preferences.getString("profiles", "[]") ?: "[]") }
            loaded = true
        } catch (_: Exception) { problem = "自定义音色配置读取失败，原记录已保留；内置风格仍可使用。" }
    }
    fun persist(next: List<SpeechProfile>, after: () -> Unit = {}) {
        if (!loaded || saving) return
        saving = true; problem = ""
        scope.launch {
            try {
                val json = SpeechProfiles.encode(next)
                check(withContext(Dispatchers.IO) { preferences.edit().putString("profiles", json).commit() }) { "保存失败，请重试" }
                custom = next; after()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { problem = failure.message ?: "音色配置保存失败" }
            finally { saving = false }
        }
    }
    Text("声音风格", style = MaterialTheme.typography.titleSmall)
    Text("先选择在线音色或系统基础声线，再应用调音风格。这里的预设只调整语速与音高，不改变说话人，也不是独立训练音色。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("女声风格", "男声风格", "我的风格").forEach { label ->
            FilterChip(group == label, onClick = { group = label }, label = { Text(label) })
        }
    }
    val shown = if (group == "我的风格") custom else SpeechProfiles.builtIn.filter { it.group == group }
    Column {
        shown.forEach { profile ->
            val selected = profile.matches(settings)
            Surface(onClick = { onUpdate { profile.apply(it) } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    RadioButton(selected = selected, onClick = null)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${profile.description} · ${"%.2f".format(profile.rate)}× / 音高 ${"%.2f".format(profile.pitch)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (group == "我的风格") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !saving, onClick = { editingId = profile.id; name = profile.name; editor = true }) { Text("改名") }
                TextButton(enabled = !saving, onClick = {
                    persist(custom.filterNot { it.id == profile.id }) {
                        if (settings.ttsProfile == profile.id) onUpdate { it.copy(ttsProfile = "") }
                    }
                }) { Text("删除 ${profile.name}") }
            }
        }
        if (group == "我的风格" && custom.isEmpty()) Text("调整基础声线、语速和音高后，可保存为自己的配置。",
            style = MaterialTheme.typography.bodySmall)
    }
    TextButton(enabled = loaded && !saving && custom.size < SpeechProfiles.MAX_CUSTOM,
        onClick = { editingId = null; name = ""; editor = !editor }) { Text("将当前声音保存为我的风格") }
    if (editor) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true,
            label = { Text("自定义名称（最多 30 字）") }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !saving && name.isNotBlank(), onClick = {
                val cleanName = name.trim()
                if (custom.any { it.id != editingId && it.name == cleanName }) { problem = "已有同名配置，请换个名称" }
                else {
                    val previous = custom.firstOrNull { it.id == editingId }
                    val profile = previous?.copy(name = cleanName) ?: SpeechProfile("custom-${UUID.randomUUID()}", cleanName,
                        "已保存的基础声线与调音", "我的风格", settings.ttsRate, settings.ttsPitch, settings.ttsVoice)
                    val next = if (previous != null) custom.map { if (it.id == previous.id) profile else it } else custom + profile
                    persist(next) {
                        if (previous == null) onUpdate { profile.apply(it) }
                        editor = false; group = "我的风格"
                    }
                }
            }) { Text(if (saving) "保存中…" else "保存") }
            TextButton(enabled = !saving, onClick = { editor = false }) { Text("取消") }
        }
    }
    if (problem.isNotBlank()) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
