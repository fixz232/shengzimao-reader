package org.shengzimao.reader;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.pcdd.sonovel.context.HttpClientContext;
import com.pcdd.sonovel.context.BookContext;
import com.pcdd.sonovel.context.OkHttpClientFactory;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.Crawler;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.parser.BookParser;
import com.pcdd.sonovel.parser.ChapterParser;
import com.pcdd.sonovel.parser.SearchParser;
import com.pcdd.sonovel.parser.TocParser;
import com.pcdd.sonovel.utils.SourceUtils;
import com.caoccao.javet.interop.V8Host;
import okhttp3.Dns;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

/** Independently written adapter; upstream sources and binary remain unchanged. */
public final class ReaderEngine {
    private static final boolean ALLOW_PRIVATE = "1".equals(System.getenv("SZM_ALLOW_PRIVATE_URLS"));
    private static List<InetAddress> publicAddresses(String host) throws UnknownHostException {
        List<InetAddress> addresses = Dns.SYSTEM.lookup(host);
        if (!ALLOW_PRIVATE) for (InetAddress address : addresses) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                throw new UnknownHostException("拒绝访问内网地址：" + host);
        }
        return addresses;
    }
    private static List<SearchResult> search(String keyword) throws Exception {
        // Preserve parser metadata; the desktop ranks and filters title/author
        // together, including compound queries that source search boxes cannot handle.
        List<SearchResult> results = Collections.synchronizedList(new ArrayList<>());
        String first = keyword.strip().split("\\s+")[0];
        List<String> queries = first.equals(keyword) ? List.of(keyword) : List.of(keyword, first);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var source : SourceUtils.getSearchableSources()) futures.add(executor.submit(() -> {
                for (String query : queries) {
                    try { results.addAll(new SearchParser(source.config).parse(query)); }
                    catch (Exception error) { System.err.println(source.rule.getName() + "：" + error.getMessage()); }
                }
            }));
            for (var future : futures) future.get();
        }
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        for (var result : results) unique.putIfAbsent(result.getSourceId() + "|" + result.getUrl(), result);
        return new ArrayList<>(unique.values());
    }
    private static Map<String, Object> details(AppConfig config, String url) {
        config.setSourceId(SourceUtils.getRule(url).getId());
        var book = new BookParser(config).parse(url);
        List<Chapter> chapters = new TocParser(config).parseAll(url);
        if (chapters.isEmpty() || chapters.size() > 30000) throw new IllegalStateException("源站目录为空或超过 30000 章上限。");
        var rule = SourceUtils.getRule(url);
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("bookName", book.getBookName()); metadata.put("author", book.getAuthor());
        metadata.put("intro", book.getIntro()); metadata.put("cover", book.getCoverUrl());
        metadata.put("category", book.getCategory()); metadata.put("latestChapter", book.getLatestChapter());
        metadata.put("lastUpdateTime", book.getLastUpdateTime()); metadata.put("status", book.getStatus());
        metadata.put("sourceName", rule.getName()); metadata.put("url", url);
        List<Map<String,String>> toc = chapters.stream().map(chapter -> Map.of("title", chapter.getTitle(), "url", chapter.getUrl())).toList();
        return Map.of("book", metadata, "toc", toc, "chapterCount", toc.size());
    }
    private static Map<String,Object> append(AppConfig config, JSONObject input) throws Exception {
        String url=input.getStr("url");
        config.setSourceId(SourceUtils.getRule(url).getId()); config.setExtName("txt");
        // Crawler normally supplies this context. Incremental parsing needs it too.
        var source=SourceUtils.getRule(url);
        if(source.getCrawl()!=null){
            var crawl=source.getCrawl();
            if(crawl.getMinInterval()!=null&&crawl.getMaxInterval()!=null&&crawl.getMaxInterval()<=crawl.getMinInterval())
                crawl.setMaxInterval(crawl.getMinInterval()+1);
            if(crawl.getRetryMinInterval()!=null&&crawl.getRetryMaxInterval()!=null&&crawl.getRetryMaxInterval()<=crawl.getRetryMinInterval())
                crawl.setRetryMaxInterval(crawl.getRetryMinInterval()+1);
        }
        BookContext.set(new BookParser(config).parse(url));
        var data=input.getJSONArray("chapters");
        if(data==null || data.isEmpty() || data.size()>30000) throw new IllegalArgumentException("增量章节列表无效。");
        // The adapter only accepts a contiguous suffix of the current source TOC.
        List<Chapter> toc=new TocParser(config).parseAll(url);
        int start=input.getInt("start",-1);
        if(start<0 || start+data.size()>toc.size())throw new IllegalArgumentException("源站目录已变化，请重新检查更新。");
        var parser=new ChapterParser(config);
        StringBuilder text=new StringBuilder();
        for(int i=0;i<data.size();i++){
            JSONObject requested=data.getJSONObject(i); Chapter current=toc.get(start+i);
            if(!current.getUrl().equals(requested.getStr("url")) || !current.getTitle().equals(requested.getStr("title")))
                throw new IllegalStateException("源站目录已变化，已取消追加。");
            Chapter parsed=parser.parse(current);
            if(parsed==null || parsed.getContent()==null || parsed.getContent().isBlank())throw new IllegalStateException("章节下载失败，原正文未改动。");
            text.append(parsed.getContent()).append("\n\n");
            if(text.length()>128*1024*1024)throw new IllegalStateException("新增正文超过 128 MB 上限。");
        }
        Path output=Path.of("downloads","incremental.txt").toAbsolutePath();
        Files.createDirectories(output.getParent()); Files.writeString(output,text,StandardCharsets.UTF_8);
        return Map.of("file",output.toString(),"count",data.size());
    }
    public static void main(String[] args) {
        PrintStream protocol=System.out;System.setOut(System.err);
        Map<String,Object> response=new LinkedHashMap<>();int status=0;
        try {
            JSONObject input=JSONUtil.parseObj(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            AppConfig config=AppConfigLoader.APP_CONFIG;
            HttpClientContext.set(OkHttpClientFactory.create(config).newBuilder().dns(ReaderEngine::publicAddresses)
                .addInterceptor(chain -> { publicAddresses(chain.request().url().host()); return chain.proceed(chain.request()); })
                .build());
            if(!"probe".equals(input.getStr("action","probe")))try(var runtime=V8Host.getV8Instance().createV8Runtime()){}
            switch(input.getStr("action","probe")){
                case "probe" -> response.put("data",Map.of("version",config.getVersion(),"sources",SourceUtils.getActivatedRules().size()));
                case "search" -> response.put("data",search(input.getStr("keyword")));
                case "details" -> response.put("data",details(config,input.getStr("url")));
                case "update" -> response.put("data",append(config,input));
                case "download" -> {
                    String url=input.getStr("url");config.setSourceId(SourceUtils.getRule(url).getId());
                    String format="epub".equals(input.getStr("format"))?"epub":"txt";config.setExtName(format);config.setTxtEncoding("UTF-8");
                    Map<String,Object> metadata=details(config,url);
                    double seconds=new Crawler(config).crawl(url);
                    if(seconds<=0)throw new IllegalStateException("源站没有可下载章节。");
                    Files.writeString(Path.of("download-metadata.json"),JSONUtil.toJsonStr(metadata),StandardCharsets.UTF_8);
                    try(var files=Files.list(Path.of(config.getDownloadPath()))){
                        response.put("data",files.filter(Files::isRegularFile).filter(file->file.toString().toLowerCase().endsWith("."+format)).map(file->file.toAbsolutePath().toString()).toList());
                    }
                }
                default -> throw new IllegalArgumentException("未知引擎操作。");
            }
            response.put("ok",true);
        }catch(Throwable error){response.put("ok",false);response.put("error",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());status=1;}
        finally{HttpClientContext.clear();BookContext.clear();}
        protocol.println("SZM_RESULT:"+Base64.getEncoder().encodeToString(JSONUtil.toJsonStr(response).getBytes(StandardCharsets.UTF_8)));protocol.flush();System.exit(status);
    }
}
