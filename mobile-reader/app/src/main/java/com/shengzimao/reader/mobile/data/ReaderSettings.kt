package com.shengzimao.reader.mobile.data

import android.content.Context
import android.graphics.Color
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min

/**
 * 内置阅读主题。底色与正文色成对定义，保证对比度；
 * 用户自定义配色通过 [ReaderSettings.customBackground]/[ReaderSettings.customForeground] 叠在 [CUSTOM] 之上。
 */
enum class ReaderTheme(val label: String) {
    PAPER("纸白"),
    SEPIA("护眼"),
    GREEN("豆绿"),
    NIGHT("夜间"),
    BLACK("纯黑"),
    CUSTOM("自定义");

    /** 页面底色。CUSTOM 需要配合设置里的自定义色，单独取值时退回纸白。 */
    val background: Long
        get() = when (this) {
            PAPER -> 0xFFF7F5F0
            SEPIA -> 0xFFF3E7CE
            GREEN -> 0xFFDDEBD6
            NIGHT -> 0xFF1B1E24
            BLACK -> 0xFF000000
            CUSTOM -> 0xFFF7F5F0
        }

    val onBackground: Long
        get() = when (this) {
            PAPER -> 0xFF26282E
            SEPIA -> 0xFF47392A
            GREEN -> 0xFF27301F
            NIGHT -> 0xFFB9C0CC
            BLACK -> 0xFFAEB4BE
            CUSTOM -> 0xFF26282E
        }

    val isDark: Boolean
        get() = this == NIGHT || this == BLACK
}

enum class PageAnimation(val label: String) {
    SLIDE("平移"),
    COVER("覆盖"),
    FADE("淡入"),
    SIMULATE("轻翻"),
    NONE("无动画"),
}

enum class FontChoice(val label: String) {
    SYSTEM_SANS("黑体"),
    SYSTEM_SERIF("宋体"),
    SYSTEM_MONO("等宽"),
    CUSTOM("自定义"),
}

/** 翻页方式：点击/左右滑动、滚动、单独靠音量键。 */
enum class TurnMode(val label: String) {
    TAP("点按翻页"),
    HORIZONTAL("左右翻页"),
    SCROLL("上下滚动"),
    NONE("仅音量键"),
}

/** 点按翻页的热区划分。 */
enum class TapZone(val label: String) {
    LEFT_RIGHT("左右分区"),
    TOP_BOTTOM("上下分区");

    fun isVertical(): Boolean = this == TOP_BOTTOM
}

/** 点按落在哪里：上一页 / 下一页 / 呼出菜单。 */
enum class TapTarget { PREV, NEXT, MENU }

/** 页码样式：全书页码、本章页码、全书百分比。 */
enum class PageIndicator(val label: String) {
    BOOK("全书页码"),
    CHAPTER("本章页码"),
    PERCENT("全书百分比");

    /** 是否需要在页脚显示进度百分比。 */
    val showsPercent: Boolean get() = this == PERCENT
}

/** 阅读页左边缘的手势用途（常见于“左右边缘拖动调亮度”）。选中后该方向的滑动翻页会停用。 */
enum class EdgeGesture(val label: String) {
    NONE("无"),
    BRIGHTNESS("亮度"),
}

data class ReaderSettings(
    // ---------- 排版 ----------
    val fontSize: Int = 20,
    val lineSpacingPercent: Int = 160,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val customBackground: Long = 0xFFF5F0E6,
    val customForeground: Long = 0xFF2A2620,
    val font: FontChoice = FontChoice.SYSTEM_SERIF,
    /** 导入的字体文件（应用私有 fonts 目录下的文件名）。 */
    val customFontId: String = "",
    /** 导入字体时的展示名，仅用于界面显示。 */
    val customFontName: String = "",
    val fontBold: Boolean = false,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val paddingDp: Int = 20,
    val indentParagraphs: Boolean = true,
    val cleanupEnabled: Boolean = false,
    val cleanupRules: String = "",
    /** 字号变化时按比例联动行距，避免调大字号后行距显得过挤。 */
    val lineSpacingFollowsFont: Boolean = true,
    // ---------- 交互 ----------
    val turnMode: TurnMode = TurnMode.TAP,
    val tapZone: TapZone = TapZone.LEFT_RIGHT,
    val volumeKeysTurnPages: Boolean = true,
    val keepScreenOn: Boolean = false,
    val fullScreen: Boolean = true,
    /** 翻页时显示短暂的位置浮层（“第 3 章 · 12%”）。 */
    val showPageHint: Boolean = true,
    /** 页脚显示章节名与页码。 */
    val showFooter: Boolean = true,
    val pageIndicator: PageIndicator = PageIndicator.BOOK,
    /** 左右边缘拖动时的用途（亮度调节等）。 */
    val edgeGesture: EdgeGesture = EdgeGesture.BRIGHTNESS,
    // ---------- 亮度 ----------
    /** 应用内亮度 0..1；小于 0 表示跟随系统。 */
    val screenBrightness: Float = -1f,
    /** 夜间自动切换深色主题；夜间模式同时锁定系统深色外观，避免闪白。 */
    val nightMode: Boolean = false,
    // ---------- 朗读 ----------
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    /** 选中的系统音色名；空串表示跟随引擎默认。 */
    val ttsVoice: String = "",
    /** Listening tuning profile. Empty means manual tuning; a profile is not a trained voice. */
    val ttsProfile: String = "",
    /** 朗读到页尾自动翻页续读。 */
    val autoTtsTurnPage: Boolean = true,
    /** 朗读时轮到当前段就高亮。 */
    val ttsHighlight: Boolean = true,
    // ---------- 书库 ----------
    val sort: String = "recent",
    val gridView: Boolean = true,
    /** 界面外观风格（液态玻璃 / 墨水纸感 / Material 表达 / 极简黑白）。与阅读底色 theme 相互独立。 */
    val appThemeStyle: String = "LIQUID_GLASS",
    /** 玻璃质感：液态 / 拟态 / 磨砂。仅在液态玻璃风格下生效。 */
    val glassMode: String = "LIQUID",
    /** 配色方案：极光 / 蜜桃 / 薄荷 / 暮霞 / 星夜 / 透明。 */
    val glassPalette: String = "AURORA",
    /** 氛围：同时决定背景色与动态粒子（晴朗/伤感/热烈/雨夜/雾晨/雪落/樱吹/萤火）。 */
    val atmosphere: String = "RAINY",
    /** 深浅外观：AUTO 跟随系统 / LIGHT 白色 / DARK 深色。默认跟随系统，浅色系统即白底。 */
    val appearance: String = "AUTO",
    // ---------- 在线下载 ----------
    /** 番茄 / 在线下载的并发章节数。调高更快，但更容易触发站点限速。 */
    val downloadConcurrency: Int = 4,
    val ttsSleepMinutes: Int = 0,
    val downloadWifiOnly: Boolean = false,
    val downloadAutoResume: Boolean = false,
) {
    /** 实际渲染用的底色。 */
    val pageBackground: Long get() = if (theme == ReaderTheme.CUSTOM) customBackground else theme.background

    /** 实际渲染用的正文色。 */
    val pageForeground: Long get() = if (theme == ReaderTheme.CUSTOM) customForeground else theme.onBackground

    /** 页面是否应按深色处理（系统栏图标、状态行文字）。 */
    val isDarkPage: Boolean get() = if (theme == ReaderTheme.CUSTOM) luminance(customBackground) < 0.5f else theme.isDark

    val lineSpacingMultiplier: Float get() = lineSpacingPercent / 100f

    companion object {
        private fun channel(color: Long, shift: Int): Float {
            val value = ((color shr shift) and 0xFF).toInt() / 255f
            return if (value <= 0.03928f) value / 12.92f
            else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }

        /** 相对亮度（WCAG），用于判断底色深浅。不依赖 android.graphics.Color，单测可直接运行。 */
        fun luminance(color: Long): Float =
            0.2126f * channel(color, 16) + 0.7152f * channel(color, 8) + 0.0722f * channel(color, 0)

        /** 对比度，用于自定义配色时提示“正文与底色太接近”。 */
        fun contrast(a: Long, b: Long): Float {
            val l1 = luminance(a)
            val l2 = luminance(b)
            val hi = max(l1, l2)
            val lo = min(l1, l2)
            return (hi + 0.05f) / (lo + 0.05f)
        }

        /** 字号联动行距：按同一比例缩放行距百分比，结果夹在允许范围内。 */
        fun withFontSize(settings: ReaderSettings, size: Int): ReaderSettings {
            val next = size.coerceIn(12, 40)
            if (!settings.lineSpacingFollowsFont || settings.fontSize <= 0) return settings.copy(fontSize = next)
            val scaled = (settings.lineSpacingPercent.toFloat() * next / settings.fontSize).toInt()
            return settings.copy(fontSize = next, lineSpacingPercent = scaled.coerceIn(100, 260))
        }
    }
}

private val Context.readerDataStore by preferencesDataStore(name = "reader_preferences")

/**
 * 阅读偏好存在 DataStore；书库索引存在文件。两者分开，避免频繁写大 JSON。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val FONT_SIZE = intPreferencesKey("fontSize")
        val LINE_SPACING = intPreferencesKey("lineSpacing")
        val THEME = stringPreferencesKey("theme")
        val CUSTOM_BG = intPreferencesKey("customBackground")
        val CUSTOM_FG = intPreferencesKey("customForeground")
        val FONT = stringPreferencesKey("font")
        val CUSTOM_FONT_ID = stringPreferencesKey("customFontId")
        val CUSTOM_FONT_NAME = stringPreferencesKey("customFontName")
        val BOLD = booleanPreferencesKey("fontBold")
        val ANIMATION = stringPreferencesKey("pageAnimation")
        val PADDING = intPreferencesKey("padding")
        val INDENT = booleanPreferencesKey("indentParagraphs")
        val LINE_FOLLOWS_FONT = booleanPreferencesKey("lineSpacingFollowsFont")
        val TURN_MODE = stringPreferencesKey("turnMode")
        val TAP_ZONE = stringPreferencesKey("tapZone")
        val VOLUME_KEYS = booleanPreferencesKey("volumeKeys")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keepScreenOn")
        val FULL_SCREEN = booleanPreferencesKey("fullScreen")
        val PAGE_HINT = booleanPreferencesKey("showPageHint")
        val FOOTER = booleanPreferencesKey("showFooter")
        val PAGE_INDICATOR = stringPreferencesKey("pageIndicator")
        val EDGE_GESTURE = stringPreferencesKey("edgeGesture")
        val BRIGHTNESS = floatPreferencesKey("screenBrightness")
        val NIGHT_MODE = booleanPreferencesKey("nightMode")
        val SORT = stringPreferencesKey("sort")
        val GRID = booleanPreferencesKey("gridView")
        val APP_THEME_STYLE = stringPreferencesKey("appThemeStyle")
        val GLASS_MODE = stringPreferencesKey("glassMode")
        val GLASS_PALETTE = stringPreferencesKey("glassPalette")
        val ATMOSPHERE = stringPreferencesKey("atmosphere")
        val APPEARANCE = stringPreferencesKey("appearance")
        val TTS_RATE = floatPreferencesKey("ttsRate")
        val TTS_PITCH = floatPreferencesKey("ttsPitch")
        val TTS_VOICE = stringPreferencesKey("ttsVoice")
        val TTS_PROFILE = stringPreferencesKey("ttsProfile")
        val TTS_AUTO_TURN = booleanPreferencesKey("autoTtsTurnPage")
        val TTS_HIGHLIGHT = booleanPreferencesKey("ttsHighlight")
        val DOWNLOAD_CONCURRENCY = intPreferencesKey("downloadConcurrency")
        val DOWNLOAD_AUTO_RESUME = booleanPreferencesKey("downloadAutoResume")
        val DOWNLOAD_WIFI = booleanPreferencesKey("downloadWifiOnly")
        val TTS_SLEEP = intPreferencesKey("ttsSleepMinutes")
    }

    private val store get() = context.readerDataStore

    suspend fun load(): ReaderSettings {
        val prefs = store.data.first()
        return ReaderSettings(
            fontSize = prefs[Keys.FONT_SIZE]?.coerceIn(12, 40) ?: 20,
            lineSpacingPercent = prefs[Keys.LINE_SPACING]?.coerceIn(100, 260) ?: 160,
            theme = prefs[Keys.THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.PAPER,
            customBackground = prefs[Keys.CUSTOM_BG]?.toLong().takeIf { it != null && it != 0L } ?: 0xFFF5F0E6,
            customForeground = prefs[Keys.CUSTOM_FG]?.toLong().takeIf { it != null && it != 0L } ?: 0xFF2A2620,
            font = prefs[Keys.FONT]?.let { runCatching { FontChoice.valueOf(it) }.getOrNull() } ?: FontChoice.SYSTEM_SERIF,
            customFontId = prefs[Keys.CUSTOM_FONT_ID] ?: "",
            customFontName = prefs[Keys.CUSTOM_FONT_NAME] ?: "",
            fontBold = prefs[Keys.BOLD] ?: false,
            pageAnimation = prefs[Keys.ANIMATION]?.let { runCatching { PageAnimation.valueOf(it) }.getOrNull() } ?: PageAnimation.SLIDE,
            paddingDp = prefs[Keys.PADDING]?.coerceIn(8, 40) ?: 20,
            indentParagraphs = prefs[Keys.INDENT] ?: true,
            lineSpacingFollowsFont = prefs[Keys.LINE_FOLLOWS_FONT] ?: true,
            turnMode = prefs[Keys.TURN_MODE]?.let { runCatching { TurnMode.valueOf(it) }.getOrNull() } ?: TurnMode.TAP,
            tapZone = prefs[Keys.TAP_ZONE]?.let { runCatching { TapZone.valueOf(it) }.getOrNull() } ?: TapZone.LEFT_RIGHT,
            volumeKeysTurnPages = prefs[Keys.VOLUME_KEYS] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
            fullScreen = prefs[Keys.FULL_SCREEN] ?: true,
            showPageHint = prefs[Keys.PAGE_HINT] ?: true,
            showFooter = prefs[Keys.FOOTER] ?: true,
            pageIndicator = prefs[Keys.PAGE_INDICATOR]?.let { runCatching { PageIndicator.valueOf(it) }.getOrNull() } ?: PageIndicator.BOOK,
            edgeGesture = prefs[Keys.EDGE_GESTURE]?.let { runCatching { EdgeGesture.valueOf(it) }.getOrNull() } ?: EdgeGesture.BRIGHTNESS,
            screenBrightness = prefs[Keys.BRIGHTNESS]?.coerceIn(-1f, 1f) ?: -1f,
            nightMode = prefs[Keys.NIGHT_MODE] ?: false,
            sort = prefs[Keys.SORT] ?: "recent",
            gridView = prefs[Keys.GRID] ?: true,
            appThemeStyle = prefs[Keys.APP_THEME_STYLE] ?: "LIQUID_GLASS",
            glassMode = prefs[Keys.GLASS_MODE] ?: "LIQUID",
            glassPalette = prefs[Keys.GLASS_PALETTE] ?: "AURORA",
            atmosphere = prefs[Keys.ATMOSPHERE] ?: "RAINY",
            appearance = prefs[Keys.APPEARANCE] ?: "AUTO",
            ttsRate = prefs[Keys.TTS_RATE]?.coerceIn(0.5f, 2.5f) ?: 1f,
            ttsPitch = prefs[Keys.TTS_PITCH]?.coerceIn(0.5f, 2f) ?: 1f,
            ttsVoice = prefs[Keys.TTS_VOICE] ?: "",
            ttsProfile = prefs[Keys.TTS_PROFILE] ?: "",
            autoTtsTurnPage = prefs[Keys.TTS_AUTO_TURN] ?: true,
            ttsHighlight = prefs[Keys.TTS_HIGHLIGHT] ?: true,
            downloadConcurrency = (prefs[Keys.DOWNLOAD_CONCURRENCY] ?: 4).coerceIn(1, 8),
            downloadWifiOnly = prefs[Keys.DOWNLOAD_WIFI] ?: false,
            downloadAutoResume = prefs[Keys.DOWNLOAD_AUTO_RESUME] ?: false,
            ttsSleepMinutes = (prefs[Keys.TTS_SLEEP] ?: 0).coerceIn(0, 180),
        )
    }

    suspend fun save(settings: ReaderSettings) {
        store.edit { prefs ->
            prefs[Keys.FONT_SIZE] = settings.fontSize
            prefs[Keys.LINE_SPACING] = settings.lineSpacingPercent
            prefs[Keys.THEME] = settings.theme.name
            prefs[Keys.CUSTOM_BG] = settings.customBackground.toInt()
            prefs[Keys.CUSTOM_FG] = settings.customForeground.toInt()
            prefs[Keys.FONT] = settings.font.name
            prefs[Keys.CUSTOM_FONT_ID] = settings.customFontId
            prefs[Keys.CUSTOM_FONT_NAME] = settings.customFontName
            prefs[Keys.BOLD] = settings.fontBold
            prefs[Keys.ANIMATION] = settings.pageAnimation.name
            prefs[Keys.PADDING] = settings.paddingDp
            prefs[Keys.INDENT] = settings.indentParagraphs
            prefs[Keys.LINE_FOLLOWS_FONT] = settings.lineSpacingFollowsFont
            prefs[Keys.TURN_MODE] = settings.turnMode.name
            prefs[Keys.TAP_ZONE] = settings.tapZone.name
            prefs[Keys.VOLUME_KEYS] = settings.volumeKeysTurnPages
            prefs[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[Keys.FULL_SCREEN] = settings.fullScreen
            prefs[Keys.PAGE_HINT] = settings.showPageHint
            prefs[Keys.FOOTER] = settings.showFooter
            prefs[Keys.PAGE_INDICATOR] = settings.pageIndicator.name
            prefs[Keys.EDGE_GESTURE] = settings.edgeGesture.name
            prefs[Keys.BRIGHTNESS] = settings.screenBrightness
            prefs[Keys.NIGHT_MODE] = settings.nightMode
            prefs[Keys.SORT] = settings.sort
            prefs[Keys.GRID] = settings.gridView
            prefs[Keys.APP_THEME_STYLE] = settings.appThemeStyle
            prefs[Keys.GLASS_MODE] = settings.glassMode
            prefs[Keys.GLASS_PALETTE] = settings.glassPalette
            prefs[Keys.ATMOSPHERE] = settings.atmosphere
            prefs[Keys.APPEARANCE] = settings.appearance
            prefs[Keys.TTS_RATE] = settings.ttsRate
            prefs[Keys.TTS_PITCH] = settings.ttsPitch
            prefs[Keys.TTS_VOICE] = settings.ttsVoice
            prefs[Keys.TTS_PROFILE] = settings.ttsProfile
            prefs[Keys.TTS_AUTO_TURN] = settings.autoTtsTurnPage
            prefs[Keys.TTS_HIGHLIGHT] = settings.ttsHighlight
            prefs[Keys.DOWNLOAD_CONCURRENCY] = settings.downloadConcurrency
            prefs[Keys.DOWNLOAD_WIFI] = settings.downloadWifiOnly
            prefs[Keys.DOWNLOAD_AUTO_RESUME] = settings.downloadAutoResume
            prefs[Keys.TTS_SLEEP] = settings.ttsSleepMinutes
        }
    }
}
