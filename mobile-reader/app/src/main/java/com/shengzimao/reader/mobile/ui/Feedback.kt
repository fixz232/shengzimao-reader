package com.shengzimao.reader.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 底部轻提示：2.6 秒后自动消失，避免打断翻页。 */
@Composable
fun MessageToast(text: String, error: Boolean, onDismiss: () -> Unit) {
    var visible by remember(text) { mutableStateOf(true) }
    LaunchedEffect(text) {
        visible = true
        delay(2600)
        visible = false
        onDismiss()
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 72.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 3.dp,
            ) {
                Text(
                    text = text,
                    color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp).testTag("toast"),
                )
            }
        }
    }
}

/** 书库中的阻塞式提示（例如导入结果汇总），需要用户确认。 */
@Composable
fun MessageDialog(text: String, error: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (error) "操作未完成" else "完成") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
