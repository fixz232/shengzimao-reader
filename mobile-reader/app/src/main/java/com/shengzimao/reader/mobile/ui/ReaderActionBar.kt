package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Shared action positions in local/online novel and comic readers. */
@Composable
internal fun ReaderActionBar(
    color: Color,
    onPrevious: () -> Unit,
    onCatalog: () -> Unit,
    onMiddle: () -> Unit,
    middleIcon: ImageVector,
    middleLabel: String,
    onSettings: () -> Unit,
    onNext: () -> Unit,
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ReaderAction("上章", Icons.AutoMirrored.Filled.ArrowBack, color, previousEnabled, onPrevious)
        ReaderAction("目录", Icons.AutoMirrored.Filled.ListAlt, color, true, onCatalog)
        ReaderAction(middleLabel, middleIcon, color, true, onMiddle)
        ReaderAction("设置", Icons.Default.Tune, color, true, onSettings)
        ReaderAction("下章", Icons.AutoMirrored.Filled.ArrowForward, color, nextEnabled, onNext)
    }
}

@Composable
private fun RowScope.ReaderAction(label: String, icon: ImageVector, color: Color, enabled: Boolean, action: () -> Unit) {
    Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = action).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val foreground = if (enabled) color else color.copy(alpha = .38f)
        Icon(icon, null, Modifier.size(22.dp), tint = foreground)
        Text(label, style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 2,
            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}
