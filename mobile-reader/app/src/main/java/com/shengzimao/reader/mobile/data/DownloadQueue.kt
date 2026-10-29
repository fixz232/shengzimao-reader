package com.shengzimao.reader.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class DownloadStatus(val label: String) {
    QUEUED("等待中"), RUNNING("下载中"), PAUSED("已暂停"), DONE("已完成"), FAILED("下载失败"), CANCELLED("已取消"), INTERRUPTED("已中断"), SKIPPED("已在书库")
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val kind: String = "novel",
    val title: String,
    val source: String,
    val key: String,
    val payload: String,
    val format: String = "txt",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "等待前面的任务完成",
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val bookId: String = "",
    val wifiOnly: Boolean = false,
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
    val failedPages: Int = 0,
) {
    val active get() = status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED)
    fun json() = JSONObject().put("id", id).put("kind", kind).put("title", title).put("source", source)
        .put("key", key).put("payload", payload).put("format", format).put("status", status.name)
        .put("done", done).put("total", total).put("message", message).put("createdAt", createdAt)
        .put("startedAt", startedAt).put("finishedAt", finishedAt).put("bookId", bookId)
        .put("wifiOnly", wifiOnly).put("pagesDone", pagesDone).put("pagesTotal", pagesTotal).put("failedPages", failedPages)

    companion object {
        fun parse(j: JSONObject) = DownloadTask(
            id = j.getString("id"), kind = j.optString("kind", "novel"), title = j.getString("title"),
            source = j.optString("source"), key = j.getString("key"), payload = j.getString("payload"),
            format = j.optString("format", "txt"), status = DownloadStatus.valueOf(j.getString("status")),
            done = j.optInt("done"), total = j.optInt("total"), message = j.optString("message"),
            createdAt = j.optLong("createdAt"), startedAt = j.optLong("startedAt"),
            finishedAt = j.optLong("finishedAt"), bookId = j.optString("bookId"),
            wifiOnly = j.optBoolean("wifiOnly"),
            pagesDone = j.optInt("pagesDone"), pagesTotal = j.optInt("pagesTotal"), failedPages = j.optInt("failedPages"),
        )
    }
}

data class DownloadOutcome(val bookId: String, val added: Boolean = true)

/** 下载进度写入队列的最小间隔：进度条不需要每章都刷新。 */
private const val PROGRESS_INTERVAL_MS = 250L

/** 单本有界并发、多本串行；重开可按设置恢复中断任务，保留用户的暂停与取消。 */
class DownloadQueue(
    private val file: File,
    private val scope: CoroutineScope,
    private val canRun: (DownloadTask) -> Boolean = { true },
    private val onEnqueued: () -> Unit = {},
    private val defaultWifiOnly: () -> Boolean = { false },
    private val execute: suspend (DownloadTask, (Int, Int, String) -> Unit) -> DownloadOutcome,
) {
    private val mutable = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks = mutable.asStateFlow()
    private val mutableStorageError = MutableStateFlow("")
    val storageError = mutableStorageError.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val saves = Channel<List<DownloadTask>>(Channel.CONFLATED)
    private var activeId = ""
    private var active: Deferred<DownloadOutcome>? = null
    private var loaded = false

    init {
        scope.launch(Dispatchers.IO) {
            for (snapshot in saves) {
                try {
                    atomicJson(file, JSONArray().also { a -> snapshot.forEach { a.put(it.json()) } }.toString())
                    mutableStorageError.value = ""
                } catch (failure: Exception) { mutableStorageError.value = "下载记录无法保存：${failure.message ?: "存储空间不足"}。退出后可能无法恢复记录。" }
            }
        }
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.isFile) emptyList() else {
                        val a = JSONArray(file.readText())
                        (0 until minOf(a.length(), 300)).mapNotNull { i -> runCatching { DownloadTask.parse(a.getJSONObject(i)) }.getOrNull() }
                    }
                }.getOrElse { failure ->
                    mutableStorageError.value = "旧下载记录无法读取，已保留原文件：${failure.message.orEmpty()}"
                    // 记录恢复失败时不覆盖原件，下一次状态变更前先保存副本。
                    if (file.isFile) runCatching { file.copyTo(File(file.parentFile, "tasks-unreadable-${System.currentTimeMillis()}.json")) }
                    emptyList()
                }
            }.map { if (it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)) it.copy(status = DownloadStatus.INTERRUPTED, message = "上次退出时未完成，继续可复用已下载章节和图片") else it }
            mutable.update { existing -> (existing + restored.filter { old -> existing.none { it.id == old.id } }).take(300) }
            loaded = true
            persist()
            wake.trySend(Unit)
            for (signal in wake) {
                while (isActive) {
                    val queued = mutable.value.filter { it.status == DownloadStatus.QUEUED }
                    if (queued.isEmpty()) break
                    val task = queued.firstOrNull(canRun)
                    if (task == null) { kotlinx.coroutines.delay(2000); continue }
                    activeId = task.id
                    patch(task.id) { it.copy(status = DownloadStatus.RUNNING, startedAt = System.currentTimeMillis(), message = "正在连接并读取目录…", pagesDone = 0, pagesTotal = 0, failedPages = 0) }
                    try {
                        active = scope.async(Dispatchers.IO) {
                            var lastTotal = -1
                            var lastAt = 0L
                            var savedAt = 0L
                            execute(task) { done, total, message ->
                                // 进度回调在下载热路径上：节流 + 只在数值变化时更新，
                                // 避免每章都重建整个任务列表并触发 UI 重组。
                                val now = System.currentTimeMillis()
                                val finished = done >= total && total > 0
                                if (!finished && total == lastTotal && now - lastAt < PROGRESS_INTERVAL_MS) return@execute
                                lastTotal = total
                                lastAt = now
                                mutable.update { all -> all.map { if (it.id == task.id && it.status == DownloadStatus.RUNNING) it.copy(done = done, total = total, message = message) else it } }
                                if (finished || now - savedAt >= 1500) { savedAt = now; persist() }
                            }
                        }
                        val outcome = active!!.await()
                        patch(task.id) { current ->
                            if (current.status != DownloadStatus.RUNNING) current else current.copy(
                                status = if (outcome.added) DownloadStatus.DONE else DownloadStatus.SKIPPED,
                                message = if (outcome.added) "已完成，可在书库打开" else "已在书库中，保留原内容",
                                bookId = outcome.bookId, done = current.total, finishedAt = System.currentTimeMillis(),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        if (!isActive) throw cancelled
                        patch(task.id) { if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.CANCELLED, message = "任务已取消", finishedAt = System.currentTimeMillis()) else it }
                    } catch (failure: Exception) {
                        patch(task.id) { if (it.status != DownloadStatus.RUNNING) it else if (!canRun(task)) it.copy(status = DownloadStatus.QUEUED, message = "网络中断，等待符合下载条件的网络后继续")
                            else it.copy(status = DownloadStatus.FAILED, message = failure.message ?: "下载未完成，请重试", finishedAt = System.currentTimeMillis()) }
                    } finally { active = null; activeId = "" }
                }
            }
        }
    }

    fun enqueue(tasks: List<DownloadTask>): Int {
        var added = 0
        mutable.update { old ->
            val keys = old.filter { it.active }.map { it.kind + ":" + it.key }.toMutableSet()
            val fresh = tasks.take(30).filter { keys.add(it.kind + ":" + it.key) }.map { it.copy(wifiOnly = it.wifiOnly || defaultWifiOnly()) }
            require(old.count { it.active } + fresh.size <= 100) { "队列最多保留 100 个等待或运行任务" }
            added = fresh.size
            (old.filter { it.active } + fresh + old.filterNot { it.active }).take(300)
        }
        persist(); if (added > 0) onEnqueued(); wake.trySend(Unit)
        return added
    }

    fun cancel(id: String) {
        patch(id) { if (it.active) it.copy(status = DownloadStatus.CANCELLED, message = "已取消，继续可复用已下载章节 / 图片", finishedAt = System.currentTimeMillis()) else it }
        if (activeId == id) active?.cancel()
    }

    fun retry(id: String) {
        val old = mutable.value.firstOrNull { it.id == id && (!it.active || it.status == DownloadStatus.PAUSED) } ?: return
        if (mutable.value.any { it.id != id && it.active && it.kind == old.kind && it.key == old.key }) return
        if (mutable.value.count { it.id != id && it.active } >= 100) {
            patch(id) { it.copy(message = "队列已有 100 个活动任务，请稍后重试") }
            return
        }
        patch(id) { it.copy(status = DownloadStatus.QUEUED, startedAt = 0, finishedAt = 0, message = "等待继续，自动校验并复用已完成内容") }
        onEnqueued()
        wake.trySend(Unit)
    }

    /** Only interrupted work is resumed; an explicit pause/cancel is always respected. */
    fun resumeInterrupted() = scope.launch {
        while (!loaded) kotlinx.coroutines.delay(50)
        var resumed = 0
        mutable.update { all ->
            val keys = all.filter { it.active }.map { it.kind + ":" + it.key }.toMutableSet()
            var remaining = (100 - all.count { it.active }).coerceAtLeast(0)
            resumed = 0
            all.map { task ->
                if (task.status == DownloadStatus.INTERRUPTED && remaining > 0 && keys.add(task.kind + ":" + task.key)) {
                    remaining--; resumed++
                    task.copy(status = DownloadStatus.QUEUED, startedAt = 0, finishedAt = 0,
                        message = "等待继续，自动校验并复用已完成内容")
                } else task
            }
        }
        if (resumed > 0) { persist(); onEnqueued(); wake.trySend(Unit) }
    }

    fun reportPages(id: String, done: Int, total: Int, failed: Int) {
        mutable.update { tasks -> tasks.map { if (it.id == id && it.status == DownloadStatus.RUNNING)
            it.copy(pagesDone = done, pagesTotal = total, failedPages = failed) else it } }
        if (failed > 0 || done == total) persist()
    }

    fun pause(id: String) {
        patch(id) { if (it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)) it.copy(status = DownloadStatus.PAUSED,
            message = "已暂停，已完成内容保留", finishedAt = System.currentTimeMillis()) else it }
        if (activeId == id) active?.cancel()
    }
    fun prioritize(id: String) {
        mutable.update { all ->
            val task = all.firstOrNull { it.id == id && it.status == DownloadStatus.QUEUED } ?: return@update all
            all.filter { it.status == DownloadStatus.RUNNING } + task + all.filterNot { it.id == id || it.status == DownloadStatus.RUNNING }
        }; persist(); wake.trySend(Unit)
    }

    fun clearHistory() { mutable.update { all -> all.filter { it.active } }; persist() }
    fun remove(id: String) { cancel(id); mutable.update { all -> all.filterNot { it.id == id } }; persist() }
    private fun patch(id: String, change: (DownloadTask) -> DownloadTask) { mutable.update { all -> all.map { if (it.id == id) change(it) else it } }; persist() }
    private fun persist() { if (loaded) saves.trySend(mutable.value) }
}

// 串行提交索引和任务快照，避免多个写入者同时替换同一目标文件。
@Synchronized
internal fun atomicJson(file: File, json: String) {
    val target = file.absoluteFile
    val parent = checkNotNull(target.parentFile)
    parent.mkdirs()
    // 同一目录的多个写入者不能共用 .tmp，否则一次 rename 会移走另一次的临时文件。
    val temp = java.nio.file.Files.createTempFile(parent.toPath(), target.name + ".", ".tmp")
    try {
        temp.toFile().writeText(json, Charsets.UTF_8)
        try {
            java.nio.file.Files.move(temp, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temp, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        java.nio.file.Files.deleteIfExists(temp)
    }
}
