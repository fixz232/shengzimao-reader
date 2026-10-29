package com.shengzimao.reader.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ColorScheme

internal fun contrastRatio(a: Color, b: Color): Float {
    val x = a.luminance(); val y = b.luminance()
    return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
}

internal fun readableAccent(color: Color, background: Color): Color {
    val opaque = color.copy(alpha = 1f)
    if (contrastRatio(opaque, background) >= 4.5f) return opaque
    val target = if (contrastRatio(Color.Black, background) > contrastRatio(Color.White, background)) Color.Black else Color.White
    for (step in 1..100) {
        val candidate = lerp(opaque, target, step / 100f)
        if (contrastRatio(candidate, background) >= 4.5f) return candidate
    }
    return target
}

internal fun onAccent(color: Color): Color =
    if (contrastRatio(Color.Black, color) > contrastRatio(Color.White, color)) Color.Black else Color.White

/** Related surfaces in a theme share a luminance family; check the entire range. */
internal fun readableOnSurfaces(color: Color, surfaces: List<Color>): Color {
    val opaque = color.copy(alpha = 1f)
    fun score(candidate: Color) = surfaces.minOf { contrastRatio(candidate, it) }
    if (score(opaque) >= 4.5f) return opaque
    val target = if (score(Color.Black) > score(Color.White)) Color.Black else Color.White
    for (step in 1..100) {
        val candidate = lerp(opaque, target, step / 100f)
        if (score(candidate) >= 4.5f) return candidate
    }
    return target
}

internal fun readableSurfaceScheme(base: ColorScheme): ColorScheme {
    val surfaces = listOf(base.surface, base.surfaceVariant, base.background, base.surfaceContainerLowest,
        base.surfaceContainerLow, base.surfaceContainer, base.surfaceContainerHigh, base.surfaceContainerHighest)
    return base.copy(onSurface = readableOnSurfaces(base.onSurface, surfaces),
        onSurfaceVariant = readableOnSurfaces(base.onSurfaceVariant, surfaces),
        onBackground = readableAccent(base.onBackground, base.background))
}
