package com.shengzimao.reader.mobile.reader

/** Real speakers from Microsoft's public Read Aloud voice catalog, not pitch presets. */
internal data class OnlineSpeechVoice(
    val shortName: String,
    val name: String,
    val description: String,
    val locale: String,
) {
    val id: String get() = "edge:$shortName"
}

internal object OnlineSpeechVoices {
    val all = listOf(
        OnlineSpeechVoice("zh-CN-XiaoxiaoNeural", "晓晓", "女声 · 普通话 · 温和自然", "zh-CN"),
        OnlineSpeechVoice("zh-CN-XiaoyiNeural", "晓伊", "女声 · 普通话 · 清亮轻快", "zh-CN"),
        OnlineSpeechVoice("zh-TW-HsiaoChenNeural", "晓辰", "女声 · 台湾华语 · 柔和叙事", "zh-TW"),
        OnlineSpeechVoice("zh-CN-YunxiNeural", "云希", "男声 · 普通话 · 自然叙事", "zh-CN"),
        OnlineSpeechVoice("zh-CN-YunjianNeural", "云健", "男声 · 普通话 · 沉稳有力", "zh-CN"),
        OnlineSpeechVoice("zh-CN-YunyangNeural", "云扬", "男声 · 普通话 · 清晰旁白", "zh-CN"),
    )

    fun find(id: String): OnlineSpeechVoice? = all.firstOrNull { it.id == id }
    fun isOnline(id: String): Boolean = id.startsWith("edge:")
}
