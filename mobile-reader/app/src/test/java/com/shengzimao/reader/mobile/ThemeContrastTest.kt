package com.shengzimao.reader.mobile

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.shengzimao.reader.mobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Test

class ThemeContrastTest {
    private fun assertContrast(label: String, scheme: ColorScheme) {
        listOf(
            "primary" to (scheme.primary to scheme.onPrimary),
            "secondary" to (scheme.secondary to scheme.onSecondary),
            "tertiary" to (scheme.tertiary to scheme.onTertiary),
            "container" to (scheme.primaryContainer to scheme.onPrimaryContainer),
            "body" to (scheme.surface to scheme.onSurface),
            "secondary text" to (scheme.surfaceVariant to scheme.onSurfaceVariant),
        ).forEach { (role, pair) -> assertTrue("$label/$role contrast=${contrastRatio(pair.first, pair.second)}", contrastRatio(pair.first, pair.second) >= 4.5f) }
    }
    @Test fun brandAndMonoControlsAndTextRemainReadable() {
        listOf(BrandLightScheme, BrandDarkScheme, MonoLightScheme, MonoDarkScheme).forEachIndexed { i, scheme -> assertContrast("scheme$i", scheme) }
    }
    @Test fun allGlassPalettesAndAtmospheresHaveReadableControlsInBothModes() {
        for (dark in listOf(false, true)) {
            val palettes = GlassPaletteId.entries.map { auroraPalette(it, dark) } + Atmosphere.entries.mapNotNull { it.palette(dark) }
            palettes.forEachIndexed { i, palette ->
                val scheme = glassScheme(palette, dark)
                assertContrast("glass$dark/$i", scheme)
                assertTrue(contrastRatio(scheme.primary, scheme.surfaceContainerHighest) >= 4.5f)
                assertEquals(1f, scheme.primaryContainer.alpha)
            }
        }
    }
    @Test fun accentCorrectionIsStableForAlreadyReadableColors() {
        assertEquals(Color.Black, readableAccent(Color.Black, Color.White))
        assertEquals(Color.White, readableAccent(Color.White, Color.Black))
    }

    @Test fun secondaryTextRemainsReadableAcrossEveryContentSurface() {
        val schemes = listOf(BrandLightScheme, BrandDarkScheme, MonoLightScheme, MonoDarkScheme) +
            listOf(false, true).flatMap { dark ->
                (GlassPaletteId.entries.map { auroraPalette(it, dark) } + Atmosphere.entries.mapNotNull { it.palette(dark) })
                    .map { readableSurfaceScheme(glassScheme(it, dark)) }
            }
        schemes.forEachIndexed { index, base ->
            val scheme = readableSurfaceScheme(base)
            val surfaces = listOf(scheme.surface, scheme.background, scheme.surfaceVariant, scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)
            for (surface in surfaces) for (text in listOf(scheme.onSurface, scheme.onSurfaceVariant)) {
                assertTrue("scheme $index: text must be readable on all card, input and sheet surfaces", contrastRatio(text, surface) >= 4.5f)
            }
        }
    }

    @Test fun panelsPreserveTheSelectedNonGlassTheme() {
        for (dark in listOf(false, true)) for (style in AppThemeStyle.entries.filter { it != AppThemeStyle.LIQUID_GLASS }) {
            val base = if (style == AppThemeStyle.MONO) {
                if (dark) MonoDarkScheme else MonoLightScheme
            } else if (dark) BrandDarkScheme else BrandLightScheme
            for (palette in GlassPaletteId.entries) {
                val panel = dialogColorSchemeFor(base, style, dark, auroraPalette(palette, dark))
                assertEquals("panel must retain the selected theme's surface", base.surface, panel.surface)
                assertEquals("glass palette must not change another theme's controls", base.primary, panel.primary)
                assertContrast("panel $style/$dark", panel)
            }
        }
    }

    @Test fun allGlassPanelsHaveReadableAccentControlsAndOpaqueSurfaces() {
        for (dark in listOf(false, true)) {
            val palettes = GlassPaletteId.entries.map { auroraPalette(it, dark) } + Atmosphere.entries.mapNotNull { it.palette(dark) }
            for (palette in palettes) {
                val panel = dialogColorSchemeFor(glassScheme(palette, dark), AppThemeStyle.LIQUID_GLASS, dark, palette)
                assertContrast("glass panel $dark", panel)
                assertEquals(1f, panel.surface.alpha)
                assertTrue(contrastRatio(panel.primary, panel.surface) >= 4.5f)
            }
        }
    }
}
