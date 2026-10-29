package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import dalvik.system.PathClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 只加载原 APK 的纯解密工具类，不启动原应用、不读取应用数据。 */
object PrivateRuleOracleProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).canonicalFile
        require(root.path.startsWith("/data/local/tmp/szm-private-rule-probe-") && root.isDirectory)
        val apk = File(root, "original.apk")
        check(mangaHashBytes(apk.readBytes()) == "f71b526e87f3640e1734f5dc1e875027008a9a7a54d81329d3365c48672cc205")
        val loader = PathClassLoader(apk.path, ClassLoader.getSystemClassLoader())
        val beanClass = loader.loadClass("com.wsw.cospa.bean.ComicSourceBean")
        val setUrl = beanClass.getMethod("setBookSourceUrl", String::class.java)
        val decrypt = loader.loadClass("com.wsw.cospa.utils.for").getMethod("for", String::class.java, beanClass)
        val fixtures = JSONArray(File(root, "sources.json").readText())
        var fields = 0
        var rebound = 0
        for (i in 0 until fixtures.length()) {
            val row = fixtures.getJSONObject(i)
            val source = row.getJSONObject("source")
            val effective = row.getString("encryptionBaseUrl")
            val bean = beanClass.getConstructor().newInstance()
            setUrl.invoke(bean, effective)
            if (effective != source.getString("bookSourceUrl")) rebound++
            val hashes = row.getJSONObject("decodedSha256")
            for (key in hashes.keys()) {
                val actual = decrypt.invoke(null, source.getString(key), bean) as? String ?: error("original decoder returned null at $i $key")
                check(mangaHash(actual) == hashes.getString(key)) { "independent hash mismatch at $i $key" }
                check(actual == LegacyRuleCrypto.readRule(source, key)) { "codec mismatch at $i $key" }
                fields++
            }
        }
        val vectors = JSONObject(File(root, "vectors.json").readText())
        val synthetic = vectors.getJSONArray("vectors")
        for (i in 0 until synthetic.length()) {
            val row = synthetic.getJSONObject(i)
            val bean = beanClass.getConstructor().newInstance()
            setUrl.invoke(bean, row.getString("url"))
            val original = decrypt.invoke(null, row.getString("raw"), bean) as? String
            check(original == row.getString("plain"))
            check(original == LegacyRuleCrypto.decode(row.getString("raw"), row.getString("url")))
        }
        val materials = vectors.getJSONArray("materials")
        val hexDecode = loader.loadClass("android.support.v4.wd").getMethod("case", String::class.java)
        val sevenClass = loader.loadClass("com.wsw.cospa.utils.goto")
        val seven = sevenClass.getConstructor().newInstance()
        val unpack = sevenClass.getMethod("for", ByteArray::class.java)
        for (i in 0 until materials.length()) {
            val row = materials.getJSONObject(i)
            val original = unpack.invoke(seven, hexDecode.invoke(null, row.getString("url"))) as String
            check(original == LegacyRuleCrypto.hostMaterial(row.getString("url"))) { "host packing mismatch at vector $i" }
        }
        val converted = LegadoConverter.convertAll(JSONArray((0 until fixtures.length()).map { fixtures.getJSONObject(it).getJSONObject("source") })).first
        val baozi = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }.first { it.getInt("catalogIndex") == 165 }.getJSONObject("source")
        val runtime = LegacyRuleRuntime()
        val directory = "<h2 class=section-title>章节目录</h2><div><a class=comics-chapters__item href='/c'>第一话</a></div>"
        val chapter = runtime.evaluate(LegacyRuleCrypto.readRule(baozi, "ruleChapterList"), directory, baozi.getString("bookSourceUrl"), true)
        check(chapter.size == 1 && chapter.single().contains("href=\"/c\""))
        val page = "<amp-state id=chapter1><script type='application/json'>{\"url\":\"https://i.baozicdn.com/p.jpg\"}</script></amp-state>"
        check(runtime.evaluate(LegacyRuleCrypto.readRule(baozi, "ruleBookContent"), page, baozi.getString("bookSourceUrl"), true) == listOf("https://i.baozimh.com/p.jpg"))
        check(runtime.script("typeof Packages+'|'+typeof JavaImporter+'|'+typeof java.lang", "", "https://fixture.example") == listOf("undefined|undefined|undefined"))
        println("PRIVATE_RULE_ORACLE_OK sources=${fixtures.length()} fields=$fields rebound=$rebound synthetic=${synthetic.length()} materials=${materials.length()} enabled=${converted.count { it.enabled }}")
        println("PRIVATE_RULE_ART_RUNTIME_OK baoziDirectory=1 baoziImages=1 sandbox=closed")
    }
    private fun mangaHashBytes(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
