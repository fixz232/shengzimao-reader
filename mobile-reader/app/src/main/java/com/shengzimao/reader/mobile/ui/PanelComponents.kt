package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.ui.theme.DialogTheme
import com.shengzimao.reader.mobile.ui.theme.LocalAppTokens

/** Shared opaque sheet, bounded to the window; each body retains its own scroll state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppModalSheet(onDismissRequest: () -> Unit, title: String? = null,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    DialogTheme {
        val tokens = LocalAppTokens.current
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.heightIn(max = modalPageHeight(.94f)),
            shape = RoundedCornerShape(topStart = tokens.radii.sheet, topEnd = tokens.radii.sheet),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = { WindowInsets.ime },
        ) {
            if (title != null) Box(Modifier.padding(horizontal = tokens.spacing.page)) {
                PanelHeader(title, onDismissRequest, actions = actions)
            }
            content()
        }
    }
}

@Composable
internal fun PanelHeader(title: String, onClose: () -> Unit, subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {}) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            actions()
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭$title") }
        }
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
internal fun SearchScopeButton(summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 48.dp)
            .semantics { contentDescription = "搜索范围：$summary" }) {
        Text("搜索范围", style = MaterialTheme.typography.labelLarge)
        Text(summary, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.ArrowDropDown, "选择搜索范围")
    }
}
