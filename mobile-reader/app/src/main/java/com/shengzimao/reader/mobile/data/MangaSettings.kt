package com.shengzimao.reader.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** 漫画阅读方式。 */
enum class MangaReadingMode(val label: String) {
    VERTICAL("条漫"),
    HORIZONTAL("左右翻页"),
    RTL("日漫");

    val reversed: Boolean get() = this == RTL
}

/** 单页图片的缩放策略。 */
enum class MangaScaleMode(val label: String) {
    WIDTH("适应宽度"),
    HEIGHT("适应高度"),
    FIT("整页显示"),
    ORIGINAL("原图大小");
}

/** 漫画阅读区底色。黑白灰是漫画阅读的通行三档。 */
enum class MangaBackground(val label: String, val color: Long) {
    BLACK("纯黑", 0xFF000000),
    DARK("深灰", 0xFF1A1C1F),
    LIGHT("浅灰", 0xFFE9EAEC),
    WHITE("纸白", 0xFFFFFFFF);

    val isDark: Boolean get() = this != LIGHT && this != WHITE
}

/**
 * 漫画专属阅读偏好。与小说阅读设置分开存储：
 * 漫画关心的是方向、底色、缩放与翻页手势，小说关心字号行距，
 * 共用一套会让两边的设置页都变复杂。
 */
data class MangaSettings(
    val mode: MangaReadingMode = MangaReadingMode.VERTICAL,
    val scaleMode: MangaScaleMode = MangaScaleMode.WIDTH,
    val background: MangaBackground = MangaBackground.BLACK,
    /** 图片之间的竖直间距（条漫模式），单位 dp。 */
    val gapDp: Int = 0,
    /** 页面左右留白，单位 dp。 */
    val paddingDp: Int = 0,
    val keepScreenOn: Boolean = true,
    /** 应用内亮度 0..1；小于 0 表示跟随系统。 */
    val brightness: Float = -1f,
    val volumeKeysTurnPages: Boolean = true,
    val doubleTapZoom: Boolean = true,
    /** 双击放大的目标倍数。 */
    val doubleTapScale: Float = 2.5f,
    /** 缩放时锁定平移，减少误触。 */
    val lockPan: Boolean = false,
    /** 到达本章末自动接着加载下一章（条漫连续阅读）。 */
    val autoNextChapter: Boolean = true,
    /** 阅读时隐藏顶栏与底栏，单击中央呼出。 */
    val immersive: Boolean = true,
    /** 阅读进度提示浮层。 */
    val showPageHint: Boolean = true,
    /** 预取下一章的图片张数。 */
    val prefetchPages: Int = 3,
    val prefetchWifiOnly: Boolean = true,
    val doublePage: Boolean = false,
    val autoCrop: Boolean = false,
    val smartSpreads: Boolean = true,
    val cropPercent: Int = 0,
    val libraryCompact: Boolean = false,
    val librarySort: String = "recent",
    val showStatusBar: Boolean = true,
    /** Only the pace is saved; automatic reading always starts by an explicit tap. */
    val autoReadIntervalSeconds: Int = 8,
    val autoScrollDpPerSecond: Int = 36,
) {
    val backgroundColor: Long get() = background.color
    val isDark: Boolean get() = background.isDark
}

private val Context.mangaDataStore by preferencesDataStore(name = "manga_preferences")

/** 漫画阅读偏好的独立 DataStore，key 前缀 `manga.`，与小说设置互不影响。 */
class MangaSettingsRepository(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("manga.mode")
        val SCALE_MODE = stringPreferencesKey("manga.scaleMode")
        val BACKGROUND = stringPreferencesKey("manga.background")
        val GAP = intPreferencesKey("manga.gapDp")
        val PADDING = intPreferencesKey("manga.paddingDp")
        val KEEP_SCREEN_ON = booleanPreferencesKey("manga.keepScreenOn")
        val BRIGHTNESS = floatPreferencesKey("manga.brightness")
        val VOLUME_KEYS = booleanPreferencesKey("manga.volumeKeys")
        val DOUBLE_TAP = booleanPreferencesKey("manga.doubleTapZoom")
        val DOUBLE_TAP_SCALE = floatPreferencesKey("manga.doubleTapScale")
        val LOCK_PAN = booleanPreferencesKey("manga.lockPan")
        val AUTO_NEXT = booleanPreferencesKey("manga.autoNextChapter")
        val IMMERSIVE = booleanPreferencesKey("manga.immersive")
        val PAGE_HINT = booleanPreferencesKey("manga.showPageHint")
        val PREFETCH = intPreferencesKey("manga.prefetchPages")
        val PREFETCH_WIFI = booleanPreferencesKey("manga.prefetchWifiOnly")
        val DOUBLE_PAGE = booleanPreferencesKey("manga.doublePage")
        val AUTO_CROP = booleanPreferencesKey("manga.autoCrop")
        val SMART_SPREADS = booleanPreferencesKey("manga.smartSpreads")
        val CROP = intPreferencesKey("manga.cropPercent")
        val LIBRARY_SORT = stringPreferencesKey("manga.librarySort")
        val LIBRARY_COMPACT = booleanPreferencesKey("manga.libraryCompact")
        val STATUS_BAR = booleanPreferencesKey("manga.showStatusBar")
        val AUTO_INTERVAL = intPreferencesKey("manga.autoReadIntervalSeconds")
        val AUTO_SCROLL = intPreferencesKey("manga.autoScrollDpPerSecond")
    }

    suspend fun load(): MangaSettings {
        val prefs = context.mangaDataStore.data.first()
        val defaults = MangaSettings()
        return MangaSettings(
            mode = prefs[Keys.MODE]?.let { name -> MangaReadingMode.entries.firstOrNull { it.name == name } } ?: defaults.mode,
            scaleMode = prefs[Keys.SCALE_MODE]?.let { name -> MangaScaleMode.entries.firstOrNull { it.name == name } } ?: defaults.scaleMode,
            background = prefs[Keys.BACKGROUND]?.let { name -> MangaBackground.entries.firstOrNull { it.name == name } } ?: defaults.background,
            gapDp = (prefs[Keys.GAP] ?: defaults.gapDp).coerceIn(0, 48),
            paddingDp = (prefs[Keys.PADDING] ?: defaults.paddingDp).coerceIn(0, 48),
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            brightness = (prefs[Keys.BRIGHTNESS] ?: defaults.brightness).coerceIn(-1f, 1f),
            volumeKeysTurnPages = prefs[Keys.VOLUME_KEYS] ?: defaults.volumeKeysTurnPages,
            doubleTapZoom = prefs[Keys.DOUBLE_TAP] ?: defaults.doubleTapZoom,
            doubleTapScale = (prefs[Keys.DOUBLE_TAP_SCALE] ?: defaults.doubleTapScale).coerceIn(1.5f, 4f),
            lockPan = prefs[Keys.LOCK_PAN] ?: defaults.lockPan,
            autoNextChapter = prefs[Keys.AUTO_NEXT] ?: defaults.autoNextChapter,
            immersive = prefs[Keys.IMMERSIVE] ?: defaults.immersive,
            showPageHint = prefs[Keys.PAGE_HINT] ?: defaults.showPageHint,
            prefetchPages = (prefs[Keys.PREFETCH] ?: defaults.prefetchPages).coerceIn(0, 6),
            prefetchWifiOnly = prefs[Keys.PREFETCH_WIFI] ?: defaults.prefetchWifiOnly,
            doublePage = prefs[Keys.DOUBLE_PAGE] ?: defaults.doublePage,
            autoCrop = prefs[Keys.AUTO_CROP] ?: defaults.autoCrop,
            smartSpreads = prefs[Keys.SMART_SPREADS] ?: defaults.smartSpreads,
            cropPercent = (prefs[Keys.CROP] ?: defaults.cropPercent).coerceIn(0, 15),
            libraryCompact = prefs[Keys.LIBRARY_COMPACT] ?: defaults.libraryCompact,
            librarySort = prefs[Keys.LIBRARY_SORT]?.takeIf { it in setOf("recent", "title", "added", "progress") } ?: "recent",
            showStatusBar = prefs[Keys.STATUS_BAR] ?: defaults.showStatusBar,
            autoReadIntervalSeconds = (prefs[Keys.AUTO_INTERVAL] ?: defaults.autoReadIntervalSeconds).coerceIn(2, 60),
            autoScrollDpPerSecond = (prefs[Keys.AUTO_SCROLL] ?: defaults.autoScrollDpPerSecond).coerceIn(12, 120),
        )
    }

    suspend fun save(settings: MangaSettings) {
        context.mangaDataStore.edit { prefs ->
            prefs[Keys.MODE] = settings.mode.name
            prefs[Keys.SCALE_MODE] = settings.scaleMode.name
            prefs[Keys.BACKGROUND] = settings.background.name
            prefs[Keys.GAP] = settings.gapDp
            prefs[Keys.PADDING] = settings.paddingDp
            prefs[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[Keys.BRIGHTNESS] = settings.brightness
            prefs[Keys.VOLUME_KEYS] = settings.volumeKeysTurnPages
            prefs[Keys.DOUBLE_TAP] = settings.doubleTapZoom
            prefs[Keys.DOUBLE_TAP_SCALE] = settings.doubleTapScale
            prefs[Keys.LOCK_PAN] = settings.lockPan
            prefs[Keys.AUTO_NEXT] = settings.autoNextChapter
            prefs[Keys.IMMERSIVE] = settings.immersive
            prefs[Keys.PAGE_HINT] = settings.showPageHint
            prefs[Keys.PREFETCH] = settings.prefetchPages
            prefs[Keys.PREFETCH_WIFI] = settings.prefetchWifiOnly
            prefs[Keys.DOUBLE_PAGE] = settings.doublePage
            prefs[Keys.AUTO_CROP] = settings.autoCrop
            prefs[Keys.SMART_SPREADS] = settings.smartSpreads
            prefs[Keys.CROP] = settings.cropPercent
            prefs[Keys.LIBRARY_COMPACT] = settings.libraryCompact
            prefs[Keys.LIBRARY_SORT] = settings.librarySort
            prefs[Keys.STATUS_BAR] = settings.showStatusBar
            prefs[Keys.AUTO_INTERVAL] = settings.autoReadIntervalSeconds.coerceIn(2, 60)
            prefs[Keys.AUTO_SCROLL] = settings.autoScrollDpPerSecond.coerceIn(12, 120)
        }
    }
}
