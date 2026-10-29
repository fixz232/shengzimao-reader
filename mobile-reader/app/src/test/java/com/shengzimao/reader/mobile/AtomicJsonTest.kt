package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.atomicJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicJsonTest {
    @Test fun replacesExistingIndexAndCleansTemporaryFiles() {
        val root = Files.createTempDirectory("reader-atomic-json").toFile()
        try {
            val file = File(root, "nested/library.json")
            atomicJson(file, "{\"revision\":1}")
            atomicJson(file, "{\"revision\":2}")
            assertEquals(2, JSONObject(file.readText()).getInt("revision"))
            assertEquals(listOf("library.json"), checkNotNull(file.parentFile).listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentWritersPublishCompleteIndexesWithoutSharingTemporaryFiles() {
        val root = Files.createTempDirectory("reader-atomic-parallel").toFile()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val file = File(root, "library.json")
            val payload = "原创索引正文".repeat(2000)
            atomicJson(file, JSONObject().put("writer", -1).put("payload", payload).toString())
            val start = CountDownLatch(1)
            val jobs = (0 until 6).map { writer ->
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(30) {
                        atomicJson(file, JSONObject().put("writer", writer).put("payload", payload).toString())
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
            // Windows 的普通文件输入流会阻止 rename；完成并行写入后检查最终原子快照。
            val snapshot = JSONObject(file.readText())
            assertEquals(payload, snapshot.getString("payload"))
            assertTrue(snapshot.getInt("writer") in 0 until 6)
            assertEquals(listOf("library.json"), root.listFiles()!!.map { it.name })
        } finally { pool.shutdownNow(); root.deleteRecursively() }
    }
}
