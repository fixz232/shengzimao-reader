package com.shengzimao.reader.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ReaderTheme(val label: String) {
    PAPER("纸白"),
    SEPIA("护眼"),
    GREEN("豆绿"),
    NIGHT("夜间"),
    BLACK("纯黑");

    /** 页面底色与正文颜色成对出现，保证对比度。 */
    val background: Long
        get() = when (this) {
            PAPER -> 0xFFF7F5F0
            SEPIA -> 0xFFF3E7CE
            GREEN -> 0xFFDDEBD6
            NIGHT -> 0xFF1B1E24
            BLACK -> 0xFF000000
        }

    val onBackground: Long
        get() = when (this) {
            PAPER -> 0xFF26282E
            SEPIA -> 0xFF47392A
            GREEN -> 0xFF27301F
            NIGHT -> 0xFFB9C0CC
            BLACK -> 0xFF9AA0AA
        }

    val isDark: Boolean
        get() = this == NIGHT || this == BLACK
}

enum class PageAnimation(val label: String) {
    SLIDE("平移"),
    COVER("覆盖"),
    FADE("淡入"),
    SIMULATE("仿真"),
    NONE("无动画"),
}

enum class FontChoice(val label: String) {
    SYSTEM_SANS("黑体（系统）"),
    SYSTEM_SERIF("宋体（系统）"),
    SYSTEM_MONO("等宽（系统）"),
}

data class ReaderSettings(
    val fontSize: Int = 20,
    val lineSpacingPercent: Int = 160,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val font: FontChoice = FontChoice.SYSTEM_SERIF,
    val fontBold: Boolean = false,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val paddingDp: Int = 20,
    val indentParagraphs: Boolean = true,
    val volumeKeysTurnPages: Boolean = true,
    val keepScreenOn: Boolean = false,
    val fullScreen: Boolean = true,
    val chapterTitleStyle: Boolean = true,
    val sort: String = "recent",
    val gridView: Boolean = true,
    val floatingMenu: Boolean = true,
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    val autoTtsTurnPage: Boolean = true,
) {
    val lineSpacingMultiplier: Float get() = lineSpacingPercent / 100f
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
        val FONT = stringPreferencesKey("font")
        val BOLD = booleanPreferencesKey("fontBold")
        val ANIMATION = stringPreferencesKey("pageAnimation")
        val PADDING = intPreferencesKey("padding")
        val INDENT = booleanPreferencesKey("indentParagraphs")
        val VOLUME_KEYS = booleanPreferencesKey("volumeKeys")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keepScreenOn")
        val FULL_SCREEN = booleanPreferencesKey("fullScreen")
        val CHAPTER_TITLE_STYLE = booleanPreferencesKey("chapterTitleStyle")
        val SORT = stringPreferencesKey("sort")
        val GRID = booleanPreferencesKey("gridView")
        val FLOATING_MENU = booleanPreferencesKey("floatingMenu")
        val TTS_RATE = floatPreferencesKey("ttsRate")
        val TTS_PITCH = floatPreferencesKey("ttsPitch")
        val TTS_AUTO_TURN = booleanPreferencesKey("autoTtsTurnPage")
    }

    private val store get() = context.readerDataStore

    suspend fun load(): ReaderSettings {
        val prefs = store.data.first()
        return ReaderSettings(
            fontSize = prefs[Keys.FONT_SIZE]?.coerceIn(12, 40) ?: 20,
            lineSpacingPercent = prefs[Keys.LINE_SPACING]?.coerceIn(100, 260) ?: 160,
            theme = prefs[Keys.THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.PAPER,
            font = prefs[Keys.FONT]?.let { runCatching { FontChoice.valueOf(it) }.getOrNull() } ?: FontChoice.SYSTEM_SERIF,
            fontBold = prefs[Keys.BOLD] ?: false,
            pageAnimation = prefs[Keys.ANIMATION]?.let { runCatching { PageAnimation.valueOf(it) }.getOrNull() } ?: PageAnimation.SLIDE,
            paddingDp = prefs[Keys.PADDING]?.coerceIn(8, 40) ?: 20,
            indentParagraphs = prefs[Keys.INDENT] ?: true,
            volumeKeysTurnPages = prefs[Keys.VOLUME_KEYS] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
            fullScreen = prefs[Keys.FULL_SCREEN] ?: true,
            chapterTitleStyle = prefs[Keys.CHAPTER_TITLE_STYLE] ?: true,
            sort = prefs[Keys.SORT] ?: "recent",
            gridView = prefs[Keys.GRID] ?: true,
            floatingMenu = prefs[Keys.FLOATING_MENU] ?: true,
            ttsRate = prefs[Keys.TTS_RATE]?.coerceIn(0.5f, 2.5f) ?: 1f,
            ttsPitch = prefs[Keys.TTS_PITCH]?.coerceIn(0.5f, 2f) ?: 1f,
            autoTtsTurnPage = prefs[Keys.TTS_AUTO_TURN] ?: true,
        )
    }

    suspend fun save(settings: ReaderSettings) {
        store.edit { prefs ->
            prefs[Keys.FONT_SIZE] = settings.fontSize
            prefs[Keys.LINE_SPACING] = settings.lineSpacingPercent
            prefs[Keys.THEME] = settings.theme.name
            prefs[Keys.FONT] = settings.font.name
            prefs[Keys.BOLD] = settings.fontBold
            prefs[Keys.ANIMATION] = settings.pageAnimation.name
            prefs[Keys.PADDING] = settings.paddingDp
            prefs[Keys.INDENT] = settings.indentParagraphs
            prefs[Keys.VOLUME_KEYS] = settings.volumeKeysTurnPages
            prefs[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[Keys.FULL_SCREEN] = settings.fullScreen
            prefs[Keys.CHAPTER_TITLE_STYLE] = settings.chapterTitleStyle
            prefs[Keys.SORT] = settings.sort
            prefs[Keys.GRID] = settings.gridView
            prefs[Keys.FLOATING_MENU] = settings.floatingMenu
            prefs[Keys.TTS_RATE] = settings.ttsRate
            prefs[Keys.TTS_PITCH] = settings.ttsPitch
            prefs[Keys.TTS_AUTO_TURN] = settings.autoTtsTurnPage
        }
    }
}
