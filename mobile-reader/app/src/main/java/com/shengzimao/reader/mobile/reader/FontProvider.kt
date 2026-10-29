package com.shengzimao.reader.mobile.reader

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.shengzimao.reader.mobile.data.FontChoice
import com.shengzimao.reader.mobile.data.ReaderSettings
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream

/**
 * 字体解析：内置三档系统字体 + 用户导入的字体文件。
 *
 * 导入的字体只保存在应用私有目录（files/fonts），通过 [Font] 直接读文件，
 * 因此不需要额外的存储权限，也不需要把字体内嵌进 APK。
 */
object FontProvider {

    private val cache = java.util.Collections.synchronizedMap(HashMap<String, FontFamily?>())
    const val MAX_FONT_BYTES = 32 * 1024 * 1024

    internal fun readFontBytes(input: InputStream, limit: Int = MAX_FONT_BYTES): ByteArray {
        require(limit in 1..MAX_FONT_BYTES)
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("字体导入已取消")
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            require(output.size() + count <= limit) { "字体文件过大，请选择不超过 32 MB 的字体" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun fontsDir(context: Context): File = File(context.filesDir, "fonts").apply { mkdirs() }

    /** 存一份导入字体并返回文件名；[id] 为空时用传入的标识。 */
    fun import(context: Context, id: String, bytes: ByteArray): File {
        require(bytes.size in 1024..MAX_FONT_BYTES) { "字体文件大小无效（最多 32 MB）" }
        val dir = fontsDir(context)
        val target = File(dir, "$id.ttf")
        val temp = File(dir, "$id.ttf.tmp")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        cache.remove(id)
        return target
    }

    fun delete(context: Context, id: String) {
        if (id.isBlank()) return
        runCatching { File(fontsDir(context), "$id.ttf").delete() }
        cache.remove(id)
    }

    /** 解析设置的字体；导入字体缺失时安全回退到系统宋体。 */
    fun family(context: Context, settings: ReaderSettings): FontFamily = when (settings.font) {
        FontChoice.SYSTEM_SANS -> FontFamily.SansSerif
        FontChoice.SYSTEM_SERIF -> FontFamily.Serif
        FontChoice.SYSTEM_MONO -> FontFamily.Monospace
        FontChoice.CUSTOM -> cachedCustom(context, settings.customFontId) ?: FontFamily.Serif
    }

    private fun cachedCustom(context: Context, id: String): FontFamily? {
        if (id.isBlank()) return null
        cache[id]?.let { return it }
        val file = File(fontsDir(context), "$id.ttf")
        if (!file.isFile) {
            cache[id] = null
            return null
        }
        val family = runCatching {
            // 只以 Normal 字重注册：导入字体通常没有 Medium/Bold 变体，
            // Compose 在组合期解析缺失字重会在首帧抛异常。
            val font = Font(file, FontWeight.Normal)
            FontFamily(font)
        }.getOrNull()
        cache[id] = family
        return family
    }

    /** 导入前的基本校验：能否被系统当成字体加载。 */
    fun isUsable(file: File): Boolean = runCatching { Typeface.createFromFile(file) != null }.getOrDefault(false)

    /** 校验一段字体字节：先落临时文件，交给系统解析后再清理。 */
    fun isUsable(bytes: ByteArray): Boolean {
        if (bytes.size !in 1024..MAX_FONT_BYTES) return false
        val probe = runCatching { File.createTempFile("szm-font-probe", ".ttf") }.getOrNull() ?: return false
        return runCatching {
            probe.writeBytes(bytes)
            isUsable(probe)
        }.getOrDefault(false).also { runCatching { probe.delete() } }
    }
}
