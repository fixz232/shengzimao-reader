package com.shengzimao.reader.mobile.reader

import com.shengzimao.reader.mobile.data.ReaderSettings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Tuning profiles applied to a real system voice; these are not separately trained speakers. */
internal data class SpeechProfile(
    val id: String,
    val name: String,
    val description: String,
    val group: String,
    val rate: Float,
    val pitch: Float,
    val voiceName: String? = null,
) {
    fun apply(settings: ReaderSettings) = settings.copy(
        ttsProfile = id, ttsRate = rate, ttsPitch = pitch,
        ttsVoice = voiceName ?: settings.ttsVoice,
    )

    fun matches(settings: ReaderSettings) = settings.ttsProfile == id &&
        abs(settings.ttsRate - rate) < .001f && abs(settings.ttsPitch - pitch) < .001f &&
        (voiceName == null || settings.ttsVoice == voiceName)
}

internal object SpeechProfiles {
    const val MAX_CUSTOM = 20
    const val SAMPLE = "夜色渐深，窗外的风轻轻吹过。翻开这一页，让故事陪你慢慢走远。这是圣子猫阅读的听书试听。"
    val builtIn = listOf(
        SpeechProfile("gentle", "温柔淑女", "柔和舒缓 · 古言、现言、睡前", "女声风格", .88f, 1.05f),
        SpeechProfile("cool", "清冷少御", "克制沉静 · 悬疑、仙侠", "女声风格", .96f, .96f),
        SpeechProfile("sweet", "甜美少女", "轻快明亮 · 甜宠、校园", "女声风格", 1.08f, 1.18f),
        SpeechProfile("narrator", "沉静旁白", "清晰平稳 · 名著、纪实、历史", "女声风格", .94f, 1f),
        SpeechProfile("confident", "干练御姐", "利落有力 · 都市、权谋", "女声风格", 1.02f, .91f),
        SpeechProfile("deep", "磁性大叔", "低缓厚实 · 玄幻、科幻、历史", "男声风格", .9f, .76f),
        SpeechProfile("mature", "温润青叔", "温和成熟 · 武侠、现实向", "男声风格", .96f, .87f),
        SpeechProfile("sunny", "阳光青年", "清亮轻快 · 青春、轻喜剧", "男声风格", 1.08f, 1.04f),
        SpeechProfile("storyteller", "书馆先生", "从容叙事 · 历史演义、故事", "男声风格", .84f, .84f),
        SpeechProfile("youth", "朝气少年", "轻盈明快 · 冒险、少年故事", "男声风格", 1.12f, 1.12f),
    )

    fun encode(custom: List<SpeechProfile>): String {
        require(custom.size <= MAX_CUSTOM) { "最多保存 $MAX_CUSTOM 个自定义音色配置" }
        require(custom.map { it.id }.distinct().size == custom.size) { "自定义配置 ID 重复" }
        return JSONArray().also { array -> custom.forEach { profile ->
            require(profile.id.startsWith("custom-") && profile.id.length <= 80) { "自定义配置 ID 无效" }
            require(profile.name.isNotBlank() && profile.name.length <= 30) { "名称需要 1–30 字" }
            require(profile.rate.isFinite() && profile.rate in .5f..2.5f &&
                profile.pitch.isFinite() && profile.pitch in .5f..2f) { "音色参数超出范围" }
            require(profile.voiceName != null && profile.voiceName.length <= 256) { "基础声线无效" }
            array.put(JSONObject().put("id", profile.id).put("name", profile.name)
                .put("rate", profile.rate.toDouble()).put("pitch", profile.pitch.toDouble())
                .put("voice", profile.voiceName))
        } }.toString()
    }

    fun decode(text: String): List<SpeechProfile> {
        require(text.length <= 32 * 1024) { "自定义音色配置过大" }
        val array = JSONArray(text)
        require(array.length() <= MAX_CUSTOM) { "自定义音色配置数量超限" }
        val result = (0 until array.length()).map { index -> array.getJSONObject(index).let { item ->
            SpeechProfile(item.getString("id"), item.getString("name"), "已保存的基础声线与调音", "我的风格",
                item.getDouble("rate").toFloat(), item.getDouble("pitch").toFloat(), item.getString("voice"))
        } }
        encode(result) // Same validation before accepting persisted configurations.
        return result
    }
}
