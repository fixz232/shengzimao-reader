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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        if (url == null || url.isBlank()) throw new IllegalArgumentException("缺少书籍地址。");
        AppConfig selected = buildConfig(config().getActiveRules());
        selected.setSourceId(SourceUtils.getRule(url).getId());
        return selected;
    }

    public static String dispatch(String json) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            JSONObject input = JSONUtil.parseObj(json);
            String action = input.getStr("action", "probe");
            HttpClientContext.set(OkHttpClientFactory.create(config()).newBuilder().dns(AndroidEngine::permissiveDns).build());
            switch (action) {
                case "probe" -> response.put("data", probe());
                case "search" -> response.put("data", search(input.getStr("keyword")));
                case "details" -> response.put("data", details(input.getStr("url")));
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
        return message == null || message.isBlank() ? cause.getClass().getName() : message;
    }

    private static List<InetAddress> permissiveDns(String host) throws java.net.UnknownHostException {
        return Dns.SYSTEM.lookup(host);
    }

    private static Map<String, Object> probe() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", config().getVersion());
        data.put("sources", SourceUtils.getActivatedRules().size());
        String report = initReport;
        if (report != null && !report.isBlank()) data.put("warning", report);
        return data;
    }

    private static List<SearchResult> search(String keyword) throws Exception {
        String text = keyword == null ? "" : keyword.strip();
        String first = text.isEmpty() ? text : text.split("\\s+")[0];
        List<String> queries = first.equals(text) ? List.of(text) : List.of(text, first);
        List<SearchResult> results = Collections.synchronizedList(new ArrayList<>());
        List<com.pcdd.sonovel.core.Source> sources = SourceUtils.getSearchableSources();
        int threads = Math.max(1, Math.min(6, sources.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (com.pcdd.sonovel.core.Source source : sources) {
                futures.add(executor.submit(() -> {
                    for (String query : queries) {
                        try {
                            results.addAll(new SearchParser(source.config).parse(query));
                        } catch (Exception error) {
                            System.err.println(source.rule.getName() + "：" + messageOf(error));
                        }
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
        if (url == null || url.isBlank()) throw new IllegalArgumentException("缺少书籍地址。");
        AppConfig cfg = configForUrl(url);
        Rule.Book book = new BookParser(cfg).parse(url);
        List<Chapter> chapters = new TocParser(cfg).parseAll(url);
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
        if (value != null && !value.isBlank()) target.put(key, value);
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
        ChapterParser parser = new ChapterParser(cfg);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("在线任务已取消。");
            JSONObject requested = data.getJSONObject(i);
            Chapter current = toc.get(start + i);
            if (!current.getUrl().equals(requested.getStr("url")) || !current.getTitle().equals(requested.getStr("title")))
                throw new IllegalStateException("源站目录已变化，已取消追加。");
            Chapter parsed = parser.parse(current);
            if (parsed == null || parsed.getContent() == null || parsed.getContent().isBlank())
                throw new IllegalStateException("章节下载失败，原正文未改动。");
            text.append(parsed.getContent()).append("\n\n");
            if (text.length() > 128 * 1024 * 1024) throw new IllegalStateException("新增正文超过 128 MB 上限。");
        }
        Path output = Path.of(baseDir, "incremental-" + System.nanoTime() + ".txt").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, text.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", output.toString());
        result.put("count", data.size());
        return result;
    }

    private static List<String> download(JSONObject input) throws Exception {
        String url = input.getStr("url");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("缺少书籍地址。");
        AppConfig cfg = configForUrl(url);
        String format = "epub".equals(input.getStr("format")) ? "epub" : "txt";
        cfg.setExtName(format);
        cfg.setTxtEncoding("UTF-8");
        Path downloads = Path.of(baseDir, "downloads").toAbsolutePath();
        Files.createDirectories(downloads);
        cfg.setDownloadPath(downloads.toString());
        Map<String, Object> metadata = details(url);
        // 桌面 Crawler 依赖 Java 21 虚拟线程和终端组件，不能直接在 Android 上运行。
        // 保留同一套解析规则，顺序读取并核对全部章节；EPUB 由 Android 宿主转换。
        BookContext.set(new BookParser(cfg).parse(url));
        List<Chapter> chapters = new TocParser(cfg).parseAll(url);
        if (chapters.isEmpty() || chapters.size() > 30000) throw new IllegalStateException("源站没有有效章节。");
        List<?> expected = (List<?>) metadata.get("toc");
        if (expected.size() != chapters.size()) throw new IllegalStateException("下载前目录已变化，请重试。");
        for (int i = 0; i < chapters.size(); i++) {
            Map<?, ?> item = (Map<?, ?>) expected.get(i);
            if (!chapters.get(i).getTitle().equals(item.get("title")) || !chapters.get(i).getUrl().equals(item.get("url")))
                throw new IllegalStateException("下载前目录已变化，请重试。");
        }
        ChapterParser parser = new ChapterParser(cfg);
        Path textFile = downloads.resolve("book.txt");
        long size = 0;
        try (java.io.BufferedWriter writer = Files.newBufferedWriter(textFile, StandardCharsets.UTF_8)) {
            for (Chapter chapter : chapters) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("在线任务已取消。");
                Chapter parsed = parser.parse(chapter);
                if (parsed == null || parsed.getContent() == null || parsed.getContent().isBlank())
                    throw new IllegalStateException("章节「" + chapter.getTitle() + "」下载失败，未导入不完整正文。");
                String content = parsed.getContent();
                if (!content.stripLeading().startsWith(chapter.getTitle())) writer.write(chapter.getTitle() + "\n\n");
                writer.write(content);
                writer.write("\n\n");
                size += content.length();
                if (size > 64L * 1024 * 1024) throw new IllegalStateException("正文超过入库上限。");
            }
        }
        Files.write(Path.of(baseDir, "download-metadata.json"), JSONUtil.toJsonStr(metadata).getBytes(StandardCharsets.UTF_8));
        List<String> files = new ArrayList<>();
        try (var stream = Files.list(downloads)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> file.toString().toLowerCase().endsWith(".txt"))
                    .forEach(file -> files.add(file.toAbsolutePath().toString()));
        }
        return files;
    }

    private AndroidEngine() {
    }
}
