package com.shengzimao.reader.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.shengzimao.reader.mobile.data.MangaBackground
import com.shengzimao.reader.mobile.data.MangaReadingMode
import com.shengzimao.reader.mobile.data.MangaSettings
import com.shengzimao.reader.mobile.ui.MANGA_TILE_HEIGHT
import com.shengzimao.reader.mobile.ui.MangaZoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 漫画阅读的纯逻辑回归：缩放边界、双击切换、设置默认值与枚举。 */
class MangaLogicTest {

    private fun zoom(viewportWidth: Float = 1080f, viewportHeight: Float = 1920f, contentHeight: Float = 0f) =
        MangaZoomState().apply {
            this.viewport = Size(viewportWidth, viewportHeight)
            this.contentHeight = contentHeight
        }

    @Test
    fun offsetIsClampedToTheScaledImageBounds() {
        val state = zoom()
        // 放大 2 倍：横向可平移余量为 (1080*2-1080)/2 = 540。
        state.applyZoom(2f, Offset.Zero)
        val clamped = state.clamp(Offset(5000f, 0f))
        assertEquals(540f, clamped.x, 0.01f)
        val negative = state.clamp(Offset(-5000f, 0f))
        assertEquals(-540f, negative.x, 0.01f)
    }

    @Test
    fun zoomingOutResetsOffsetInsteadOfLeavingTheImageOffscreen() {
        val state = zoom()
        state.applyZoom(3f, Offset(200f, 0f))
        assertTrue(state.isZoomed)
        state.applyZoom(0.1f, Offset.Zero)
        assertEquals(1f, state.scale, 0.001f)
        assertEquals(Offset.Zero, state.offset)
        assertFalse(state.isZoomed)
    }

    @Test
    fun scaleNeverExceedsConfiguredMaximum() {
        val state = zoom()
        repeat(20) { state.applyZoom(2f, Offset.Zero) }
        assertEquals(5f, state.scale, 0.001f)
    }

    @Test
    fun doubleTapTogglesBetweenFitAndTarget() {
        val state = MangaZoomState(doubleTapScale = 2.5f).apply { viewport = Size(1080f, 1920f) }
        assertTrue(state.toggleDoubleTap())
        assertEquals(2.5f, state.scale, 0.001f)
        assertFalse(state.toggleDoubleTap())
        assertEquals(1f, state.scale, 0.001f)
    }

    @Test
    fun tileCountCoversTheWholeLongImage() {
        // 一张 10000 像素高的长图：ceil(10000/3072) = 4 块，最后一块不足一圈也算一块。
        val expected = (10000 + MANGA_TILE_HEIGHT - 1) / MANGA_TILE_HEIGHT
        assertEquals(4, expected)
        val lastTop = (expected - 1) * MANGA_TILE_HEIGHT
        assertEquals(10000, minOf(lastTop + MANGA_TILE_HEIGHT, 10000))
    }

    @Test
    fun mangaSettingsDefaultsAreSaneForReading() {
        val settings = MangaSettings()
        assertEquals(MangaReadingMode.VERTICAL, settings.mode)
        assertTrue("漫画默认保持亮屏", settings.keepScreenOn)
        assertTrue("默认沉浸式阅读", settings.immersive)
        assertTrue(settings.background.isDark)
        assertEquals(-1f, settings.brightness, 0.001f)
    }

    @Test
    fun onlyRtLLayoutIsReversed() {
        assertTrue(MangaReadingMode.RTL.reversed)
        assertFalse(MangaReadingMode.VERTICAL.reversed)
        assertFalse(MangaReadingMode.HORIZONTAL.reversed)
    }

    @Test
    fun lightBackgroundsAreNotTreatedAsDark() {
        assertFalse(MangaBackground.WHITE.isDark)
        assertFalse(MangaBackground.LIGHT.isDark)
        assertTrue(MangaBackground.BLACK.isDark)
        assertTrue(MangaBackground.DARK.isDark)
    }
}
