package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val ShelfCoverAspectRatio = .72f

@Composable
internal fun ShelfBookTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2,
        overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun ShelfCaption(text: String, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, maxLines = 1,
        overflow = TextOverflow.Ellipsis, color = if (highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ShelfCoverProgress(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(3.dp), color = Color.White,
        trackColor = Color.Black.copy(alpha = .65f))
}
