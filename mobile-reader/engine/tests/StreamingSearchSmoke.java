import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Exercises the real wrapper against loopback sources; no Android device or external site. */
public final class StreamingSearchSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Site implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile boolean blockFallbackOnly;
        volatile boolean dropRequests;
        volatile boolean blockDownload;
        final CountDownLatch downloadStarted = new CountDownLatch(1);
        final CountDownLatch releaseDownload = new CountDownLatch(1);
        final String name;

        Site(String name) throws Exception {
            this.name = name;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/search", exchange -> {
                requests.incrementAndGet();
                if (dropRequests) { exchange.close(); return; }
                String query = java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                if (!blockFallbackOnly || !query.contains("小说")) {
                    started.countDown();
                    try { check(gate.await(15, TimeUnit.SECONDS), "Fixture release timed out"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); exchange.close(); return; }
                }
                byte[] bytes = ("<html><meta charset=UTF-8><div class=result><a href='/book/1'>原创 小说·" + name +
                    "</a><b>原创作者</b></div></html>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.createContext("/book/1", exchange -> {
                byte[] bytes = ("<html><meta charset=UTF-8><h1>原创并发书</h1><b class=author>原创作者</b>" +
                    "<div id=toc><a href='/chapter/1'>第一章</a></div></html>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.createContext("/chapter/", exchange -> {
                boolean downloading = exchange.getRequestURI().getPath().endsWith("/1");
                if (downloading && blockDownload) {
                    downloadStarted.countDown();
                    try { check(releaseDownload.await(15, TimeUnit.SECONDS), "Download fixture timed out"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); exchange.close(); return; }
                }
                byte[] bytes = ("<html><meta charset=UTF-8><h1>原创章节</h1><div id=content>" +
                    (downloading ? "DOWNLOAD_ONLY 正文第一章" : "READER_ONLY 正文第二章") + "</div></html>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
        JSONObject rule() {
            return JSONUtil.createObj().set("name", name).set("url", base())
                .set("search", JSONUtil.createObj().set("url", base() + "search?q=%s").set("method", "get")
                    .set("result", ".result").set("bookName", "a").set("author", "b").set("maxAttempts", 1))
                .set("needProxy", false).set("language", "zh-CN")
                .set("book", JSONUtil.createObj().set("url", "/book/(\\d+)").set("bookName", "h1").set("author", ".author"))
                .set("toc", JSONUtil.createObj().set("item", "#toc a"))
                .set("chapter", JSONUtil.createObj().set("title", "h1").set("content", "#content")
                    .set("paragraphTagClosed", true).set("filterTxt", "(?!)").set("filterTag", "script"))
                .set("crawl", JSONUtil.createObj().set("minInterval", 0).set("maxInterval", 0).set("concurrency", 1)
                    .set("maxAttempts", 1).set("retryMinInterval", 0).set("retryMaxInterval", 0));
        }
        public void close() { gate.countDown(); releaseDownload.countDown(); server.stop(0); executor.shutdownNow(); }
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]).toAbsolutePath();
        Path work = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(work);
        var caller = Executors.newFixedThreadPool(2);
        String previousConfig = System.getProperty("config.file");
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try (Site fast = new Site("快源"); Site slow = new Site("慢源"); Site bad = new Site("故障源");
             var loader = new URLClassLoader(new java.net.URL[] {
                 project.resolve(".tools/android-engine/wrapper.jar").toUri().toURL(),
                 project.resolve(".tools/android-engine/core-android.jar").toUri().toURL(),
                 project.resolve("../independent-reader/resources/sonovel/app.jar").normalize().toUri().toURL()
             }, ClassLoader.getPlatformClassLoader())) {
            Path rules = work.resolve("rules.json");
            bad.dropRequests = true;
            Files.writeString(rules, JSONUtil.createArray().set(fast.rule()).set(slow.rule()).set(bad.rule()).toString());
            Path config = work.resolve("active-config.ini");
            Files.writeString(config, Files.readString(project.resolve("app/src/main/assets/engine/config.ini"))
                .replace("active-rules=main.json", "active-rules=" + rules));
            System.setProperty("config.file", config.toString());
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> api = Class.forName("org.shengzimao.reader.AndroidEngine", true, loader);
            var formatter = Class.forName("org.shengzimao.reader.AndroidSearchRequests", true, loader)
                .getMethod("formatUrl", String.class, Object[].class);
            String encoded = (String) formatter.invoke(null, "https://example.invalid/search?q=%s", new Object[] { "原创 & 小说+%" });
            check(java.net.URI.create(encoded).getRawQuery().equals("q=%E5%8E%9F%E5%88%9B%20%26%20%E5%B0%8F%E8%AF%B4%2B%25"),
                "Literal keyword encoding changed query parameters");
            System.out.println("PASS literal search URL encoding for spaces, Chinese, ampersand, plus and percent");
            api.getMethod("configure", String.class, String.class).invoke(null, work.toString(), rules.toString());
            var dispatch = api.getMethod("dispatchStreaming", String.class, Consumer.class);

            // A single selected source publishes before its fallback query completes.
            fast.blockFallbackOnly = true;
            fast.gate = new CountDownLatch(1);
            CountDownLatch first = new CountDownLatch(1);
            var selected = caller.submit(() -> dispatch.invoke(null, JSONUtil.createObj().set("action", "search")
                .set("keyword", "原创 小说").set("sourceUrls", JSONUtil.createArray().set(fast.base())).toString(),
                (Consumer<String>) event -> { if (!JSONUtil.parseObj(event).getJSONArray("data").isEmpty()) first.countDown(); }));
            check(first.await(10, TimeUnit.SECONDS), "First response was withheld until fallback completed");
            check(fast.started.await(5, TimeUnit.SECONDS), "Fallback did not start");
            check(!selected.isDone(), "Fallback fixture should still be blocked");
            check(slow.requests.get() == 0, "Unselected source received a request");
            check(bad.requests.get() == 0, "Unselected failed source received a request");
            fast.gate.countDown();
            JSONObject one = JSONUtil.parseObj(selected.get(10, TimeUnit.SECONDS));
            check(one.getBool("ok", false), one.toString());
            check(one.getJSONArray("data").size() == 1, "Repeated keyword responses were not deduplicated");
            System.out.println("PASS single-source request isolation and immediate first-query response");

            // All sources: the fast response remains observable while the slow request is pending.
            slow.gate = new CountDownLatch(1);
            CountDownLatch fastVisible = new CountDownLatch(1);
            AtomicInteger lastCompleted = new AtomicInteger();
            var failures = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            var all = caller.submit(() -> dispatch.invoke(null, JSONUtil.createObj().set("action", "search")
                .set("keyword", "原创 小说").toString(), (Consumer<String>) event -> {
                    JSONObject progress = JSONUtil.parseObj(event);
                    lastCompleted.set(progress.getInt("completed"));
                    if (progress.containsKey("error")) {
                        check(bad.base().equals(progress.getStr("sourceUrl")), "Failure was attributed to the wrong source");
                        check(bad.name.equals(progress.getStr("sourceName")), "Failure source name was lost");
                        failures.add(progress.getStr("error"));
                    }
                    JSONArray data = progress.getJSONArray("data");
                    for (int i = 0; i < data.size(); i++)
                        if (data.getJSONObject(i).getStr("sourceName").equals(fast.name)) fastVisible.countDown();
                }));
            check(slow.started.await(5, TimeUnit.SECONDS), "Slow source did not start");
            check(fastVisible.await(10, TimeUnit.SECONDS), "Fast source was blocked by the slow source");
            check(!all.isDone(), "Slow fixture should still be pending");
            slow.gate.countDown();
            JSONObject both = JSONUtil.parseObj(all.get(10, TimeUnit.SECONDS));
            check(both.getBool("ok", false), both.toString());
            check(both.getJSONArray("data").size() == 2, "All-source search did not return both sources");
            check(lastCompleted.get() == 3, "Completion progress was incorrect");
            check(failures.size() == 1 && !failures.get(0).isBlank(), "Failed source was hidden by successful results");
            System.out.println("PASS all-source incremental results, visible per-source failure and complete progress");

            int fastBeforeRetry = fast.requests.get(), slowBeforeRetry = slow.requests.get();
            bad.dropRequests = false;
            AtomicInteger retryCompleted = new AtomicInteger();
            JSONObject recovered = JSONUtil.parseObj(dispatch.invoke(null, JSONUtil.createObj().set("action", "search")
                .set("keyword", "原创 小说").set("sourceUrls", JSONUtil.createArray().set(bad.base())).toString(),
                (Consumer<String>) event -> {
                    JSONObject progress = JSONUtil.parseObj(event);
                    check(!progress.containsKey("error"), "Recovered source still reports failure");
                    retryCompleted.set(progress.getInt("completed"));
                }));
            check(recovered.getBool("ok", false) && recovered.getJSONArray("data").size() == 1, "Failed-source retry did not recover");
            check(retryCompleted.get() == 1, "Retry progress included unselected sources");
            check(fast.requests.get() == fastBeforeRetry && slow.requests.get() == slowBeforeRetry, "Retry requested successful sources again");
            System.out.println("PASS failed-source-only retry and recovery without requesting successful sources");

            // Match the Android host's two class spaces and separate work roots.
            Path backgroundRoot = work.resolve("background"), readingRoot = work.resolve("reading");
            Files.createDirectories(backgroundRoot); Files.createDirectories(readingRoot);
            api.getMethod("configure", String.class, String.class).invoke(null, backgroundRoot.toString(), rules.toString());
            fast.blockDownload = true;
            var downloading = caller.submit(() -> dispatch.invoke(null, JSONUtil.createObj().set("action", "download")
                .set("url", fast.base() + "book/1").set("format", "txt").set("concurrency", 1).toString(), null));
            try (var readerLoader = new URLClassLoader(loader.getURLs(), ClassLoader.getPlatformClassLoader())) {
                check(fast.downloadStarted.await(10, TimeUnit.SECONDS), "Background download did not reach chapter fixture");
                Thread.currentThread().setContextClassLoader(readerLoader);
                Class<?> readerApi = Class.forName("org.shengzimao.reader.AndroidEngine", true, readerLoader);
                readerApi.getMethod("configure", String.class, String.class).invoke(null, readingRoot.toString(), rules.toString());
                var reading = caller.submit(() -> readerApi.getMethod("dispatch", String.class).invoke(null,
                    JSONUtil.createObj().set("action", "chapter").set("url", fast.base() + "book/1")
                        .set("chapterUrl", fast.base() + "chapter/2").set("title", "第二章").toString()));
                JSONObject read = JSONUtil.parseObj(reading.get(5, TimeUnit.SECONDS));
                check(read.getBool("ok", false) && read.getJSONObject("data").getStr("content").contains("READER_ONLY"),
                    "Foreground chapter failed while background download was pending: " + read);
                check(!downloading.isDone(), "Background download must still be blocked when reading finishes");
                fast.releaseDownload.countDown();
                JSONObject download = JSONUtil.parseObj(downloading.get(10, TimeUnit.SECONDS));
                check(download.getBool("ok", false), download.toString());
                Path file = Path.of(download.getJSONArray("data").getStr(0));
                check(file.startsWith(backgroundRoot), "Reader reconfigured the download's output directory");
                String text = Files.readString(file);
                check(text.contains("DOWNLOAD_ONLY") && !text.contains("READER_ONLY"), "Reading and download content leaked across sessions");
                System.out.println("PASS foreground reading during blocked download, isolated configuration, output and content");
            } finally {
                fast.releaseDownload.countDown();
                Thread.currentThread().setContextClassLoader(loader);
            }
        } finally {
            caller.shutdownNow();
            Thread.currentThread().setContextClassLoader(previousLoader);
            if (previousConfig == null) System.clearProperty("config.file"); else System.setProperty("config.file", previousConfig);
        }
    }
}
