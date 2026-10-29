package com.shengzimao.reader.mobile.data

import org.json.JSONObject

/** 显式字段编解码，不依赖反射；旧字段缺省时继承全局设置。 */
object ReadingPreferences {
    /**
     * 首行缩进由全局设置统一控制；阅读页没有单书缩进开关。
     * 旧版调整字号/主题时会保存完整快照，不能让其中的缩进值遮住全局开关。
     * 通用 novel 编解码仍保留该字段，供全局设置备份还原使用。
     */
    fun novelForBook(raw: String, defaults: ReaderSettings): ReaderSettings =
        novel(raw, defaults).copy(indentParagraphs = defaults.indentParagraphs)

    fun encode(s: ReaderSettings): String = JSONObject().apply {
        put("fontSize", s.fontSize)
        put("lineSpacingPercent", s.lineSpacingPercent)
        put("theme", s.theme.name)
        put("customBackground", s.customBackground)
        put("customForeground", s.customForeground)
        put("font", s.font.name)
        put("customFontId", s.customFontId)
        put("customFontName", s.customFontName)
        put("fontBold", s.fontBold)
        put("pageAnimation", s.pageAnimation.name)
        put("paddingDp", s.paddingDp)
        put("indentParagraphs", s.indentParagraphs)
        put("cleanupEnabled", s.cleanupEnabled)
        put("cleanupRules", s.cleanupRules)
        put("lineSpacingFollowsFont", s.lineSpacingFollowsFont)
        put("turnMode", s.turnMode.name)
        put("tapZone", s.tapZone.name)
        put("volumeKeysTurnPages", s.volumeKeysTurnPages)
        put("keepScreenOn", s.keepScreenOn)
        put("fullScreen", s.fullScreen)
        put("showPageHint", s.showPageHint)
        put("showFooter", s.showFooter)
        put("pageIndicator", s.pageIndicator.name)
        put("edgeGesture", s.edgeGesture.name)
        put("screenBrightness", s.screenBrightness.toDouble())
        put("nightMode", s.nightMode)
        put("ttsRate", s.ttsRate.toDouble())
        put("ttsPitch", s.ttsPitch.toDouble())
        put("ttsVoice", s.ttsVoice)
        put("ttsProfile", s.ttsProfile)
        put("autoTtsTurnPage", s.autoTtsTurnPage)
        put("ttsHighlight", s.ttsHighlight)
        put("sort", s.sort)
        put("gridView", s.gridView)
        put("downloadConcurrency", s.downloadConcurrency)
        put("ttsSleepMinutes", s.ttsSleepMinutes)
        put("downloadWifiOnly", s.downloadWifiOnly)
        put("downloadAutoResume", s.downloadAutoResume)
    }.toString()
    fun novel(raw: String, defaults: ReaderSettings = ReaderSettings()): ReaderSettings {
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return defaults
        return defaults.copy(
            fontSize = (j.optInt("fontSize", defaults.fontSize)).coerceIn(12, 40),
            lineSpacingPercent = (j.optInt("lineSpacingPercent", defaults.lineSpacingPercent)).coerceIn(100, 260),
            theme = ReaderTheme.entries.firstOrNull { it.name == j.optString("theme") } ?: defaults.theme,
            customBackground = j.optLong("customBackground", defaults.customBackground),
            customForeground = j.optLong("customForeground", defaults.customForeground),
            font = FontChoice.entries.firstOrNull { it.name == j.optString("font") } ?: defaults.font,
            customFontId = j.optString("customFontId", defaults.customFontId),
            customFontName = j.optString("customFontName", defaults.customFontName),
            fontBold = j.optBoolean("fontBold", defaults.fontBold),
            pageAnimation = PageAnimation.entries.firstOrNull { it.name == j.optString("pageAnimation") } ?: defaults.pageAnimation,
            paddingDp = (j.optInt("paddingDp", defaults.paddingDp)).coerceIn(0, 48),
            indentParagraphs = j.optBoolean("indentParagraphs", defaults.indentParagraphs),
            cleanupEnabled = j.optBoolean("cleanupEnabled", defaults.cleanupEnabled),
            cleanupRules = j.optString("cleanupRules", defaults.cleanupRules).take(12_000),
            lineSpacingFollowsFont = j.optBoolean("lineSpacingFollowsFont", defaults.lineSpacingFollowsFont),
            turnMode = TurnMode.entries.firstOrNull { it.name == j.optString("turnMode") } ?: defaults.turnMode,
            tapZone = TapZone.entries.firstOrNull { it.name == j.optString("tapZone") } ?: defaults.tapZone,
            volumeKeysTurnPages = j.optBoolean("volumeKeysTurnPages", defaults.volumeKeysTurnPages),
            keepScreenOn = j.optBoolean("keepScreenOn", defaults.keepScreenOn),
            fullScreen = j.optBoolean("fullScreen", defaults.fullScreen),
            showPageHint = j.optBoolean("showPageHint", defaults.showPageHint),
            showFooter = j.optBoolean("showFooter", defaults.showFooter),
            pageIndicator = PageIndicator.entries.firstOrNull { it.name == j.optString("pageIndicator") } ?: defaults.pageIndicator,
            edgeGesture = EdgeGesture.entries.firstOrNull { it.name == j.optString("edgeGesture") } ?: defaults.edgeGesture,
            screenBrightness = j.optDouble("screenBrightness", defaults.screenBrightness.toDouble()).toFloat().takeIf { it.isFinite() } ?: defaults.screenBrightness,
            nightMode = j.optBoolean("nightMode", defaults.nightMode),
            ttsRate = j.optDouble("ttsRate", defaults.ttsRate.toDouble()).toFloat().takeIf { it.isFinite() } ?: defaults.ttsRate,
            ttsPitch = j.optDouble("ttsPitch", defaults.ttsPitch.toDouble()).toFloat().takeIf { it.isFinite() } ?: defaults.ttsPitch,
            ttsVoice = j.optString("ttsVoice", defaults.ttsVoice),
            ttsProfile = j.optString("ttsProfile", defaults.ttsProfile),
            autoTtsTurnPage = j.optBoolean("autoTtsTurnPage", defaults.autoTtsTurnPage),
            ttsHighlight = j.optBoolean("ttsHighlight", defaults.ttsHighlight),
            sort = j.optString("sort", defaults.sort),
            gridView = j.optBoolean("gridView", defaults.gridView),
            downloadConcurrency = (j.optInt("downloadConcurrency", defaults.downloadConcurrency)).coerceIn(1, 8),
            ttsSleepMinutes = (j.optInt("ttsSleepMinutes", defaults.ttsSleepMinutes)).coerceIn(0, 120),
            downloadWifiOnly = j.optBoolean("downloadWifiOnly", defaults.downloadWifiOnly),
            downloadAutoResume = j.optBoolean("downloadAutoResume", defaults.downloadAutoResume),
        )
    }
    fun encode(s: MangaSettings): String = JSONObject().apply {
        put("mode", s.mode.name)
        put("scaleMode", s.scaleMode.name)
        put("background", s.background.name)
        put("gapDp", s.gapDp)
        put("paddingDp", s.paddingDp)
        put("keepScreenOn", s.keepScreenOn)
        put("brightness", s.brightness.toDouble())
        put("volumeKeysTurnPages", s.volumeKeysTurnPages)
        put("doubleTapZoom", s.doubleTapZoom)
        put("doubleTapScale", s.doubleTapScale.toDouble())
        put("lockPan", s.lockPan)
        put("autoNextChapter", s.autoNextChapter)
        put("immersive", s.immersive)
        put("showPageHint", s.showPageHint)
        put("prefetchPages", s.prefetchPages)
        put("prefetchWifiOnly", s.prefetchWifiOnly)
        put("doublePage", s.doublePage)
        put("autoCrop", s.autoCrop)
        put("smartSpreads", s.smartSpreads)
        put("cropPercent", s.cropPercent)
        put("libraryCompact", s.libraryCompact)
        put("showStatusBar", s.showStatusBar)
        put("autoReadIntervalSeconds", s.autoReadIntervalSeconds)
        put("autoScrollDpPerSecond", s.autoScrollDpPerSecond)
    }.toString()
    fun manga(raw: String, defaults: MangaSettings = MangaSettings()): MangaSettings {
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return defaults
        return defaults.copy(
            mode = MangaReadingMode.entries.firstOrNull { it.name == j.optString("mode") } ?: defaults.mode,
            scaleMode = MangaScaleMode.entries.firstOrNull { it.name == j.optString("scaleMode") } ?: defaults.scaleMode,
            background = MangaBackground.entries.firstOrNull { it.name == j.optString("background") } ?: defaults.background,
            gapDp = (j.optInt("gapDp", defaults.gapDp)).coerceIn(0, 48),
            paddingDp = (j.optInt("paddingDp", defaults.paddingDp)).coerceIn(0, 48),
            keepScreenOn = j.optBoolean("keepScreenOn", defaults.keepScreenOn),
            brightness = j.optDouble("brightness", defaults.brightness.toDouble()).toFloat().takeIf { it.isFinite() } ?: defaults.brightness,
            volumeKeysTurnPages = j.optBoolean("volumeKeysTurnPages", defaults.volumeKeysTurnPages),
            doubleTapZoom = j.optBoolean("doubleTapZoom", defaults.doubleTapZoom),
            doubleTapScale = j.optDouble("doubleTapScale", defaults.doubleTapScale.toDouble()).toFloat().takeIf { it.isFinite() } ?: defaults.doubleTapScale,
            lockPan = j.optBoolean("lockPan", defaults.lockPan),
            autoNextChapter = j.optBoolean("autoNextChapter", defaults.autoNextChapter),
            immersive = j.optBoolean("immersive", defaults.immersive),
            showPageHint = j.optBoolean("showPageHint", defaults.showPageHint),
            prefetchPages = (j.optInt("prefetchPages", defaults.prefetchPages)).coerceIn(0, 6),
            prefetchWifiOnly = j.optBoolean("prefetchWifiOnly", defaults.prefetchWifiOnly),
            doublePage = j.optBoolean("doublePage", defaults.doublePage),
            autoCrop = j.optBoolean("autoCrop", defaults.autoCrop),
            smartSpreads = j.optBoolean("smartSpreads", defaults.smartSpreads),
            cropPercent = (j.optInt("cropPercent", defaults.cropPercent)).coerceIn(0, 15),
            libraryCompact = j.optBoolean("libraryCompact", defaults.libraryCompact),
            showStatusBar = j.optBoolean("showStatusBar", defaults.showStatusBar),
            autoReadIntervalSeconds = j.optInt("autoReadIntervalSeconds", defaults.autoReadIntervalSeconds).coerceIn(2, 60),
            autoScrollDpPerSecond = j.optInt("autoScrollDpPerSecond", defaults.autoScrollDpPerSecond).coerceIn(12, 120),
        )
    }
}
