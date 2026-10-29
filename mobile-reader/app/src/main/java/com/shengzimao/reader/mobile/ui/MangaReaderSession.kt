package com.shengzimao.reader.mobile.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shengzimao.reader.mobile.data.MangaSettings
import kotlinx.coroutines.delay

/** Small, non-interactive footer; subscriptions exist only while the reader is visible. */
@Composable
internal fun MangaReaderStatus(chapter: String, page: Int, count: Int, autoPlaying: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var battery by remember { mutableIntStateOf(-1) }
    var clock by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(context, lifecycle) {
        var registered = false
        fun update(intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            battery = if (level >= 0 && scale > 0) (100L * level / scale).toInt().coerceIn(0, 100) else -1
        }
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) = update(intent) }
        fun register() { if (!registered) {
            val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registered = true; update(sticky)
        } }
        fun unregister() { if (registered) { context.unregisterReceiver(receiver); registered = false } }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> { active = true; register() }
            Lifecycle.Event.ON_PAUSE -> { active = false; unregister() }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (active) register()
        onDispose { lifecycle.removeObserver(observer); unregister() }
    }
    LaunchedEffect(active) { if (active) while (true) {
        clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        delay(30_000)
    } }
    Surface(modifier, color = Color(0xCC101114), contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(chapter, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            Text("${page + 1}/$count" + if (autoPlaying) " · 自动" else "", style = MaterialTheme.typography.labelSmall)
            Text(clock + if (battery >= 0) " · $battery%" else "", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaAutoReadSheet(settings: MangaSettings, onSave: (MangaSettings) -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) {
    var seconds by remember { mutableFloatStateOf(settings.autoReadIntervalSeconds.toFloat()) }
    var speed by remember { mutableFloatStateOf(settings.autoScrollDpPerSecond.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("自动阅读", style = MaterialTheme.typography.titleMedium)
            Text("条漫自动滚动，左右翻页和日漫定时翻页。拖动、缩放、打开工具或切到后台会暂停；再次进入不会自动开始。", style = MaterialTheme.typography.bodySmall)
            Text("翻页间隔 · ${seconds.toInt()} 秒")
            Slider(seconds, { seconds = it }, valueRange = 2f..60f, steps = 57)
            Text("条漫速度 · ${speed.toInt()} dp / 秒")
            Slider(speed, { speed = it }, valueRange = 12f..120f)
            Button(onClick = { onSave(settings.copy(autoReadIntervalSeconds = seconds.toInt(), autoScrollDpPerSecond = speed.toInt())); onStart() },
                modifier = Modifier.fillMaxWidth()) { Text("开始自动阅读") }
        }
    }
}
