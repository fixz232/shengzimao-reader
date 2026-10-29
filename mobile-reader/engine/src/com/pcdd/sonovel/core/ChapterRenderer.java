package com.pcdd.sonovel.core;

import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.shengzimao.reader.AndroidStrings;

/** Android reads plain text; EPUB generation belongs to the host, so no desktop template engine is initialized. */
public final class ChapterRenderer {
    private static final Pattern PARAGRAPH = Pattern.compile("<p>(.*?)</p>", Pattern.DOTALL);
    private final ChapterFilter filter;
    private final ChapterFormatter formatter;

    public ChapterRenderer(AppConfig config) {
        this.filter = new ChapterFilter(config);
        this.formatter = new ChapterFormatter(config);
    }

    public Chapter process(Chapter chapter) {
        // Source 构造器会重应用 crawl 设置；只构造一次，避免后续章节恢复无效的固定间隔。
        Chapter filtered = filter.filter(chapter);
        String formatted = formatter.format(filtered.getContent());
        chapter.setTitle(filtered.getTitle());
        chapter.setContent(renderTxtFormat(filtered.getTitle(), formatted));
        return chapter;
    }

    public String renderTxtFormat(String title, String content) {
        StringBuilder body = new StringBuilder();
        Matcher paragraphs = PARAGRAPH.matcher(content);
        while (paragraphs.find()) {
            String text = Jsoup.parseBodyFragment(paragraphs.group(1)).text();
            if (!AndroidStrings.isBlank(text)) body.append(text).append('\n');
        }
        if (body.length() == 0) body.append(AndroidStrings.strip(Jsoup.parseBodyFragment(content).wholeText()));
        if (AndroidStrings.isBlank(body.toString()) || body.toString().trim().equals(title.trim()))
            throw new IllegalStateException("章节正文为空或只有标题。");
        return title + "\n\n" + body;
    }

    public String renderTemplateFormat(String title, String content, String format) {
        if (!"txt".equals(format)) throw new IllegalArgumentException("Android 章节解析仅输出正文，其他格式由宿主导出。");
        return renderTxtFormat(title, content);
    }
}
