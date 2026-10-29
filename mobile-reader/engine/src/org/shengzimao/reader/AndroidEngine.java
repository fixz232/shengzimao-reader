package org.shengzimao.reader;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.context.BookContext;
import com.pcdd.sonovel.context.HttpClientContext;
import com.pcdd.sonovel.context.OkHttpClientFactory;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.parser.BookParser;
import com.pcdd.sonovel.parser.ChapterParser;
import com.pcdd.sonovel.parser.SearchParser;
import com.pcdd.sonovel.parser.TocParser;
import com.pcdd.sonovel.utils.SourceUtils;
import okhttp3.Dns;

import java.io.File;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 安卓版在线引擎入口（桌面版 ReaderEngine 的移植）。
 *
 * 与桌面版的关键差异：
 * 1. 宿主为上游配置提供 classpath 版本资源与私有配置绝对路径；
 *    本适配层单独构造每个书源的 AppConfig，不依赖进程当前目录。
 * 2. 不调用 System.exit、不替换 System.out（安卓上会破坏日志）。
 * 3. DNS 不拒绝内网地址：请求由本机发起。
 * 4. 书源规则的 @js: 由宿主注入的 Rhino 桥执行（上游用 Javet）。
 */
public final class AndroidEngine {

    /** JS 执行桥：由宿主注入 (script, text) -> result。 */
    public interface JsBridge {
        String call(String script, String text);
    }

    private static volatile JsBridge bridge;
    private static volatile String baseDir;
    private static volatile AppConfig config;
    private static volatile String initReport = "";
    private static volatile int downloadDone;
    private static volatile int downloadTotal;
    private static volatile okhttp3.OkHttpClient sharedClient;

    private static synchronized okhttp3.OkHttpClient httpClient() {
        if (sharedClient == null)
            sharedClient = OkHttpClientFactory.create(config()).newBuilder().dns(AndroidEngine::permissiveDns).build();
        return sharedClient;
    }

    public static int[] downloadProgress() { return new int[] { downloadDone, downloadTotal }; }

    public static void setBridge(JsBridge value) {
        bridge = value;
    }

    /** 设置工作目录与规则文件，并完成引擎配置初始化。必须在 dispatch 之前调用。 */
    public static synchronized void configure(String workDir, String rulesPath) {
        baseDir = workDir;
        new File(workDir).mkdirs();
        new File(workDir, "downloads").mkdirs();
        com.pcdd.sonovel.utils.LogUtils.configureDirectory(workDir);
        config = buildConfig(rulesPath);
        // 提前探一次上游的配置加载，把结果记下来，便于界面给出准确提示。
        initReport = probeUpstreamConfig();
    }

    /** 最近一次初始化诊断信息（空字符串表示正常）。 */
    public static String diagnostics() {
        return initReport;
    }

    /** 构造 AppConfig。字段默认值与 AppConfigLoader 的 getStrOrDefault 保持一致。 */
    private static AppConfig buildConfig(String rulesPath) {
        AppConfig created = new AppConfig();
        call(created, "setVersion", String.class, "android");
        call(created, "setAutoUpdate", Integer.class, 0);
        call(created, "setGhProxy", String.class, "");
        call(created, "setDownloadPath", String.class, new File(baseDir, "downloads").getAbsolutePath());
        call(created, "setExtName", String.class, "txt");
        call(created, "setTxtEncoding", String.class, "UTF-8");
        call(created, "setEnableProgressbar", Integer.class, 0);
        call(created, "setLanguage", String.class, "zh-CN");
        call(created, "setActiveRules", String.class, rulesPath);
        call(created, "setSearchLimit", Integer.class, 30);
        call(created, "setSearchFilter", Integer.class, 1);
        call(created, "setConcurrency", Integer.class, 4);
        call(created, "setMaxRetries", Integer.class, 1);
        call(created, "setWebEnabled", Integer.class, 0);
        call(created, "setSourceId", Integer.class, -1);
        call(created, "setProxyEnabled", Integer.class, 0);
        call(created, "setPreserveChapterCache", Integer.class, 0);
        call(created, "setEnableRetry", Integer.class, 1);
        call(created, "setMinInterval", Integer.class, 200);
        call(created, "setMaxInterval", Integer.class, 400);
        call(created, "setRetryMinInterval", Integer.class, 2000);
        call(created, "setRetryMaxInterval", Integer.class, 4000);
        call(created, "setWebPort", Integer.class, 8080);
        call(created, "setProxyHost", String.class, "");
        call(created, "setProxyPort", Integer.class, 0);
        return created;
    }

    private static void call(Object target, String setter, Class<?> type, Object value) {
        try {
            target.getClass().getMethod(setter, type).invoke(target, value);
        } catch (Throwable error) {
            // 旧版本可能没有该字段；但要留下痕迹，避免静默丢配置。
            System.err.println("[engine-config] " + setter + " failed: " + error);
        }
    }

    /** 校验上游配置初始化；SourceUtils 与 Source 也会访问上游配置。 */
    private static String probeUpstreamConfig() {
        try {
            Class<?> loader = Class.forName("com.pcdd.sonovel.core.AppConfigLoader");
            Method load = loader.getMethod("loadConfig");
            Object loaded = load.invoke(null);
            return loaded == null ? "upstream-config-null" : "";
        } catch (Throwable error) {
            return "upstream-config:" + messageOf(error);
        }
    }

    private static AppConfig config() {
        AppConfig current = config;
        if (current == null) throw new IllegalStateException("引擎尚未初始化，请重试。");
        return current;
    }

    private static AppConfig configForUrl(String url) {
        if (url == null || AndroidStrings.isBlank(url)) throw new IllegalArgumentException("缺少书籍地址。");
        AppConfig selected = buildConfig(config().getActiveRules());
        selected.setSourceId(SourceUtils.getRule(url).getId());
        return selected;
    }

    public static String dispatch(String json) {
        return dispatchStreaming(json, null);
    }

    /** Progress crosses the dex boundary using only a platform Consumer and JSON. */
    public static String dispatchStreaming(String json, java.util.function.Consumer<String> progress) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            JSONObject input = JSONUtil.parseObj(json);
            String action = input.getStr("action", "probe");
            HttpClientContext.set(httpClient());
            switch (action) {
                case "probe" -> response.put("data", probe());
                case "search" -> response.put("data", search(input.getStr("keyword"), progress, input.getJSONArray("sourceUrls")));
                case "details" -> response.put("data", details(input.getStr("url")));
                case "chapter" -> response.put("data", readChapter(input));
                case "update" -> response.put("data", append(input));
                case "download" -> response.put("data", download(input));
                default -> throw new IllegalArgumentException("未知引擎操作。");
            }
            response.put("ok", true);
        } catch (Throwable error) {
            response.put("ok", false);
            java.io.StringWriter sw = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(sw));
            response.put("error", messageOf(error));
            response.put("stack", sw.toString());
        } finally {
            HttpClientContext.clear();
            BookContext.clear();
        }
        return JSONUtil.toJsonStr(response);
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error;
        int guard = 0;
        while (cause.getCause() != null && cause.getCause() != cause && guard++ < 16) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || AndroidStrings.isBlank(message) ? cause.getClass().getName() : message;
    }

    private static List<InetAddress> permissiveDns(String host) throws java.net.UnknownHostException {
        return Dns.SYSTEM.lookup(host);
    }

    private static Map<String, Object> probe() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", config().getVersion());
        data.put("sources", SourceUtils.getActivatedRules().size());
        String report = initReport;
        if (report != null && !AndroidStrings.isBlank(report)) data.put("warning", report);
        return data;
    }

    private static List<SearchResult> search(String keyword, java.util.function.Consumer<String> progress, cn.hutool.json.JSONArray allowedUrls) throws Exception {
        String text = keyword == null ? "" : AndroidStrings.strip(keyword);
        String first = text.isEmpty() ? text : text.split("\\s+")[0];
        List<String> queries = first.equals(text) ? Collections.singletonList(text) : java.util.Arrays.asList(text, first);
        List<SearchResult> results = Collections.synchronizedList(new ArrayList<>());
        List<com.pcdd.sonovel.core.Source> sources = new ArrayList<>();
        for (com.pcdd.sonovel.core.Source source : SourceUtils.getSearchableSources()) {
            if (allowedUrls == null || allowedUrls.contains(source.rule.getUrl())) sources.add(source);
        }
        int threads = Math.max(1, Math.min(6, sources.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        Object publishLock = new Object();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (com.pcdd.sonovel.core.Source source : sources) {
                futures.add(executor.submit(() -> {
                    List<SearchResult> found = new ArrayList<>();
                    String failure = null;
                    for (String query : queries) {
                        if (Thread.currentThread().isInterrupted()) return;
                        List<SearchResult> batch;
                        AndroidSearchRequests.takeFailure();
                        try {
                            batch = new SearchParser(source.config).parse(query);
                            Throwable swallowed = AndroidSearchRequests.takeFailure();
                            if (swallowed != null) failure = messageOf(swallowed);
                        } catch (Exception error) {
                            AndroidSearchRequests.takeFailure();
                            if (Thread.currentThread().isInterrupted()) return;
                            failure = messageOf(error);
                            System.err.println(source.rule.getName() + "：" + messageOf(error));
                            continue;
                        }
                        found.addAll(batch);
                        // Publish this response before a fallback query can block on a slow site.
                        if (progress != null && !batch.isEmpty() && !Thread.currentThread().isInterrupted()) synchronized (publishLock) {
                            Map<String, Object> event = new LinkedHashMap<>();
                            event.put("data", batch);
                            event.put("completed", completed.get());
                            event.put("total", sources.size());
                            progress.accept(JSONUtil.toJsonStr(event));
                        }
                    }
                    results.addAll(found);
                    if (progress != null && !Thread.currentThread().isInterrupted()) synchronized (publishLock) {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("data", Collections.emptyList());
                        event.put("completed", completed.incrementAndGet());
                        event.put("total", sources.size());
                        event.put("sourceUrl", source.rule.getUrl());
                        event.put("sourceName", source.rule.getName());
                        if (failure != null) event.put("error", failure);
                        progress.accept(JSONUtil.toJsonStr(event));
                    }
                }));
            }
            for (Future<?> future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        for (SearchResult result : results) {
            unique.putIfAbsent(result.getSourceId() + "|" + result.getUrl(), result);
        }
        return new ArrayList<>(unique.values());
    }

    private static Map<String, Object> details(String url) {
        if (url == null || AndroidStrings.isBlank(url)) throw new IllegalArgumentException("缺少书籍地址。");
        AppConfig cfg = configForUrl(url);
        Rule.Book book = new BookParser(cfg).parse(url);
        List<Chapter> chapters = new TocParser(cfg).parseAll(url);
        return details(url, book, chapters);
    }

    private static Map<String, Object> details(String url, Rule.Book book, List<Chapter> chapters) {
        if (chapters.isEmpty() || chapters.size() > 30000) throw new IllegalStateException("源站目录为空或超过 30000 章上限。");
        Rule rule = SourceUtils.getRule(url);
        Map<String, Object> metadata = new LinkedHashMap<>();
        putText(metadata, "bookName", book.getBookName());
        putText(metadata, "author", book.getAuthor());
        putText(metadata, "intro", book.getIntro());
        putText(metadata, "cover", book.getCoverUrl());
        putText(metadata, "category", book.getCategory());
        putText(metadata, "latestChapter", book.getLatestChapter());
        putText(metadata, "lastUpdateTime", book.getLastUpdateTime());
        putText(metadata, "status", book.getStatus());
        putText(metadata, "sourceName", rule.getName());
        metadata.put("url", url);
        List<Map<String, String>> toc = new ArrayList<>(chapters.size());
        for (Chapter chapter : chapters) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", chapter.getTitle());
            item.put("url", chapter.getUrl());
            toc.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book", metadata);
        data.put("toc", toc);
        data.put("chapterCount", toc.size());
        return data;
    }

    private static void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !AndroidStrings.isBlank(value)) target.put(key, value);
    }

    private static ChapterParser chapterParser(AppConfig cfg) {
        ChapterParser parser = new ChapterParser(cfg); // Source applies the rule's crawl overrides here.
        normalizeIntervals(cfg);
        return parser;
    }

    private static void normalizeIntervals(AppConfig cfg) {
        // Upstream nextLong(origin, bound) rejects fixed intervals, including an intentional 0 ms delay.
        int min = Math.max(0, Math.min(Integer.MAX_VALUE - 1, cfg.getMinInterval()));
        cfg.setMinInterval(min);
        cfg.setMaxInterval(Math.max(min + 1, cfg.getMaxInterval()));
        int retry = Math.max(0, Math.min(Integer.MAX_VALUE - 1, cfg.getRetryMinInterval()));
        cfg.setRetryMinInterval(retry);
        cfg.setRetryMaxInterval(Math.max(retry + 1, cfg.getRetryMaxInterval()));
    }

    private static Map<String, Object> readChapter(JSONObject input) throws Exception {
        String url = input.getStr("url");
        String chapterUrl = input.getStr("chapterUrl");
        java.net.URI bookUri = java.net.URI.create(url);
        java.net.URI chapterUri = java.net.URI.create(chapterUrl);
        if (chapterUri.getHost() == null || chapterUri.getRawUserInfo() != null ||
                !("http".equalsIgnoreCase(chapterUri.getScheme()) || "https".equalsIgnoreCase(chapterUri.getScheme())) ||
                !chapterUri.getHost().equalsIgnoreCase(bookUri.getHost()))
            throw new IllegalArgumentException("章节地址不属于当前书源。");
        AppConfig cfg = configForUrl(url);
        cfg.setExtName("txt");
        BookContext.set(new BookParser(cfg).parse(url));
        Chapter chapter = Chapter.builder().url(chapterUrl).title(input.getStr("title", "未命名章节")).order(0).build();
        Chapter parsed = parseChapter(chapterParser(cfg), chapter, cfg);
        return Collections.singletonMap("content", parsed.getContent());
    }

    private static Map<String, Object> append(JSONObject input) throws Exception {
        String url = input.getStr("url");
        AppConfig cfg = configForUrl(url);
        cfg.setExtName("txt");
        BookContext.set(new BookParser(cfg).parse(url));
        var data = input.getJSONArray("chapters");
        if (data == null || data.isEmpty() || data.size() > 30000) throw new IllegalArgumentException("增量章节列表无效。");
        List<Chapter> toc = new TocParser(cfg).parseAll(url);
        int start = input.getInt("start", -1);
        if (start < 0 || start + data.size() > toc.size()) throw new IllegalArgumentException("源站目录已变化，请重新检查更新。");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("在线任务已取消。");
            JSONObject requested = data.getJSONObject(i);
            Chapter current = toc.get(start + i);
            if (!current.getUrl().equals(requested.getStr("url")) || !current.getTitle().equals(requested.getStr("title")))
                throw new IllegalStateException("源站目录已变化，已取消追加。");
            Chapter parsed = parseChapter(chapterParser(cfg), current, cfg);
            if (parsed == null || parsed.getContent() == null || AndroidStrings.isBlank(parsed.getContent()))
                throw new IllegalStateException("章节下载失败，原正文未改动。");
            text.append(parsed.getContent()).append("\n\n");
            if (text.length() > 128 * 1024 * 1024) throw new IllegalStateException("新增正文超过 128 MB 上限。");
        }
        Path output = Paths.get(baseDir, "incremental-" + System.nanoTime() + ".txt").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, text.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", output.toString());
        result.put("count", data.size());
        return result;
    }

    private static List<String> download(JSONObject input) throws Exception {
        downloadDone = 0;
        downloadTotal = 0;
        String url = input.getStr("url");
        if (url == null || AndroidStrings.isBlank(url)) throw new IllegalArgumentException("缺少书籍地址。");
        AppConfig cfg = configForUrl(url);
        String format = "epub".equals(input.getStr("format")) ? "epub" : "txt";
        int requestedConcurrency = Math.max(1, Math.min(8, input.getInt("concurrency", 4)));
        cfg.setConcurrency(requestedConcurrency);
        cfg.setExtName(format);
        cfg.setTxtEncoding("UTF-8");
        Path downloads = Paths.get(baseDir, "downloads").toAbsolutePath();
        Files.createDirectories(downloads);
        cfg.setDownloadPath(downloads.toString());
        // 桌面 Crawler 依赖 Java 21 虚拟线程和终端组件，不能直接在 Android 上运行。
        // 保留书源并发/间隔配置；EPUB 由 Android 宿主转换。
        Rule.Book book = new BookParser(cfg).parse(url);
        BookContext.set(book);
        List<Chapter> chapters = new TocParser(cfg).parseAll(url);
        if (chapters.isEmpty() || chapters.size() > 30000) throw new IllegalStateException("源站没有有效章节。");
        // 正文与元数据共用这一份目录快照，避免重复抓取整套分页目录。
        Map<String, Object> metadata = details(url, book, chapters);
        downloadTotal = chapters.size();
        // 独立 dispatcher 便于取消本次请求，连接池仍与搜索、详情共用。
        okhttp3.OkHttpClient httpClient = HttpClientContext.get().newBuilder().dispatcher(new okhttp3.Dispatcher()).build();
        int concurrency = Math.max(1, Math.min(requestedConcurrency, cfg.getConcurrency()));
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        ExecutorCompletionService<Void> completed = new ExecutorCompletionService<>(workers);
        List<Future<Void>> pending = new ArrayList<>();
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicLong size = new AtomicLong();
        AtomicBoolean stopping = new AtomicBoolean();
        Path parts = downloads.resolve("chapter-checkpoints");
        Files.createDirectories(parts);
        Path textFile = downloads.resolve("book.txt");
        try {
            for (int i = 0; i < Math.min(concurrency, chapters.size()); i++) {
                pending.add(completed.submit(() -> {
                    HttpClientContext.set(httpClient);
                    BookContext.set(book);
                    try {
                        // 每个 worker 重用配置、Source 与 renderer；每章只保留一份正文。
                        AppConfig local = buildConfig(cfg.getActiveRules());
                        local.setSourceId(cfg.getSourceId());
                        local.setExtName("txt");
                        ChapterParser parser = chapterParser(local);
                        int index;
                        while ((index = next.getAndIncrement()) < chapters.size()) {
                            if (stopping.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("在线任务已取消。");
                            Chapter original = chapters.get(index);
                            Path checkpoint = parts.resolve(checkpointName(original));
                            byte[] bytes = readCheckpoint(checkpoint);
                            if (bytes == null) {
                                Chapter parsed = parseChapter(parser, original, local);
                                String content = parsed.getContent();
                                String heading = content.stripLeading().startsWith(parsed.getTitle()) ? "" : parsed.getTitle() + "\n\n";
                                bytes = (heading + content + "\n\n").getBytes(StandardCharsets.UTF_8);
                                Path temp = Files.createTempFile(parts, "chapter-", ".part");
                                try {
                                    Files.write(temp, bytes);
                                    Files.move(temp, checkpoint, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    Files.write(parts.resolve(checkpoint.getFileName() + ".sha256"), checksum(bytes).getBytes(StandardCharsets.UTF_8));
                                } finally { Files.deleteIfExists(temp); }
                                parsed.setContent(null);
                            }
                            if (stopping.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("在线任务已取消。");
                            if (size.addAndGet(bytes.length) > 128L * 1024 * 1024)
                                throw new IllegalStateException("正文超过入库上限。");
                            // 目录对象不留正文，避免整本书驻留堆内存导致打开/下载时 OOM。
                            original.setContent(null);
                            synchronized (done) { downloadDone = done.incrementAndGet(); }
                        }
                        return null;
                    } finally {
                        HttpClientContext.clear();
                        BookContext.clear();
                    }
                }));
            }
            for (int i = 0; i < pending.size(); i++) completed.take().get();
            try (java.io.OutputStream output = Files.newOutputStream(textFile)) {
                for (int i = 0; i < chapters.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("在线任务已取消。");
                    Files.copy(parts.resolve(checkpointName(chapters.get(i))), output);
                }
            }
        } finally {
            stopping.set(true);
            for (Future<Void> future : pending) future.cancel(true);
            httpClient.dispatcher().cancelAll();
            workers.shutdownNow();
            if (!workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                System.err.println("[engine-download] workers still closing after cancellation");
            // 已校验章节保留到入库成功后的 cleanup；取消、断网、进程重启均可复用。
        }
        Files.write(Paths.get(baseDir, "download-metadata.json"), JSONUtil.toJsonStr(metadata).getBytes(StandardCharsets.UTF_8));
        List<String> files = new ArrayList<>();
        try (var stream = Files.list(downloads)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> file.toString().toLowerCase().endsWith(".txt"))
                    .forEach(file -> files.add(file.toAbsolutePath().toString()));
        }
        return files;
    }

    private static String checksum(byte[] bytes) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte value : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static String checkpointName(Chapter chapter) throws Exception {
        return checksum((chapter.getUrl() + "\u0000" + chapter.getTitle()).getBytes(StandardCharsets.UTF_8)) + ".txt";
    }
    private static byte[] readCheckpoint(Path path) throws Exception {
        Path digest = path.resolveSibling(path.getFileName() + ".sha256");
        if (!Files.isRegularFile(path) || !Files.isRegularFile(digest) || Files.size(digest) != 64 || Files.size(path) <= 0 || Files.size(path) > 8L * 1024 * 1024) return null;
        byte[] bytes = Files.readAllBytes(path);
        return checksum(bytes).equals(new String(Files.readAllBytes(digest), StandardCharsets.UTF_8).trim()) ? bytes : null;
    }

    private static Chapter parseChapter(ChapterParser parser, Chapter chapter, AppConfig cfg) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("在线任务已取消。");
        // 上游 ChapterFormatter.format 也会 new Source 并重置 crawl；复用 parser 的每章都要校正。
        normalizeIntervals(cfg);
        Chapter parsed = parser.parse(chapter);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("在线任务已取消。");
        if (parsed == null || parsed.getContent() == null || AndroidStrings.isBlank(parsed.getContent()))
            throw new IllegalStateException("章节「" + chapter.getTitle() + "」下载失败，未导入不完整正文。");
        String body = AndroidStrings.strip(parsed.getContent());
        if (body.startsWith(chapter.getTitle())) body = AndroidStrings.strip(body.substring(chapter.getTitle().length()));
        if (AndroidStrings.isBlank(body) || body.equals(chapter.getTitle()))
            throw new IllegalStateException("章节「" + chapter.getTitle() + "」只有标题，已停止下载。");
        return parsed;
    }

    private AndroidEngine() {
    }
}
