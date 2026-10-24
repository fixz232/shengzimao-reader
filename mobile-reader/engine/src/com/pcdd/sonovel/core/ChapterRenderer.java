package com.pcdd.sonovel.core;

import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/** Android reads plain text; EPUB generation belongs to the host, so no desktop template engine is initialized. */
public final class ChapterRenderer {
    private static final Pattern PARAGRAPH = Pattern.compile("<p>(.*?)</p>", Pattern.DOTALL);
    private final AppConfig config;

    public ChapterRenderer(AppConfig config) { this.config = config; }

    public Chapter process(Chapter chapter) {
        Chapter filtered = new ChapterFilter(config).filter(chapter);
        String formatted = new ChapterFormatter(config).format(filtered.getContent());
        chapter.setTitle(filtered.getTitle());
        chapter.setContent(renderTxtFormat(filtered.getTitle(), formatted));
        return chapter;
    }

    public String renderTxtFormat(String title, String content) {
        StringBuilder body = new StringBuilder();
        Matcher paragraphs = PARAGRAPH.matcher(content);
        while (paragraphs.find()) {
            String text = Jsoup.parseBodyFragment(paragraphs.group(1)).text();
            if (!text.isBlank()) body.append(text).append('\n');
        }
        if (body.length() == 0) body.append(Jsoup.parseBodyFragment(content).wholeText().strip());
        if (body.toString().isBlank() || body.toString().trim().equals(title.trim()))
            throw new IllegalStateException("章节正文为空或只有标题。");
        return title + "\n\n" + body;
    }

    public String renderTemplateFormat(String title, String content, String format) {
        if (!"txt".equals(format)) throw new IllegalArgumentException("Android 章节解析仅输出正文，其他格式由宿主导出。");
        return renderTxtFormat(title, content);
    }
}
