package com.shengzimao.reader.mobile.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 风格预览小样：用该风格自己的 token 渲染一张缩略卡片，
 * 让用户在设置页里直接看出三套风格的差别（圆角、字阶、层次、配色）。
 *
 * 预览内部临时提供该风格的 token，因此不会污染外层主题。
 */
@Composable
fun AppStylePreview(style: AppThemeStyle, modifier: Modifier = Modifier) {
    val tokens = tokensFor(style)
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = when (style) {
        AppThemeStyle.MONO -> if (dark) MonoDarkScheme else MonoLightScheme
        else -> if (dark) BrandDarkScheme else BrandLightScheme
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAppTokens provides tokens) {
        MaterialTheme(
            colorScheme = scheme,
            typography = tokens.typography,
            shapes = MaterialTheme.shapes,
        ) {
            Surface(
                shape = RoundedCornerShape(tokens.radii.card),
                color = MaterialTheme.colorScheme.surface,
                border = if (tokens.elevation.borderWidth > 0.dp) {
                    BorderStroke(tokens.elevation.borderWidth, MaterialTheme.colorScheme.outlineVariant)
                } else null,
                shadowElevation = tokens.elevation.card,
                modifier = modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(tokens.spacing.card)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(tokens.radii.cover)),
                        )
                        Spacer(Modifier.size(tokens.spacing.item))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "书名示例",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "第 3 章 · 42%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(tokens.spacing.item))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(tokens.radii.chip)),
                    )
                }
            }
        }
    }
}
