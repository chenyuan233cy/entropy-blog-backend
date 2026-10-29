package com.qichi.blog.modules.post.service;

import com.qichi.blog.modules.post.PostDtos;
import com.qichi.blog.security.HtmlSanitizer;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders markdown once, on the server, and stores the result.
 *
 * <p>Order matters: render, then sanitize, then derive the table of contents from the sanitized
 * HTML. Deriving the TOC last guarantees every anchor in the sidebar points at an id that actually
 * exists in the output.
 */
@Service
public class MarkdownService {

    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(
            TablesExtension.create(), AutolinkExtension.create());

    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().extensions(EXTENSIONS).build();
    private final HtmlSanitizer sanitizer;
    private final ObjectMapper objectMapper;

    public MarkdownService(HtmlSanitizer sanitizer, ObjectMapper objectMapper) {
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
    }

    public record Rendered(String html, String tocJson, int wordCount, int readingMinutes) {}

    public Rendered render(String markdown) {
        String source = markdown == null ? "" : markdown;
        Node document = parser.parse(source);
        String rawHtml = renderer.render(document);
        String safeHtml = sanitizer.cleanHtml(rawHtml);

        Document parsed = Jsoup.parseBodyFragment(safeHtml);
        parsed.outputSettings().prettyPrint(false);
        Element body = parsed.body();

        List<PostDtos.TocItem> toc = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int index = 0;
        for (Element heading : body.select("h1, h2, h3, h4, h5, h6")) {
            index++;
            String text = heading.text().trim();
            if (text.isEmpty()) continue;
            String id = uniqueId(text, used, index);
            heading.attr("id", id);
            int level = Integer.parseInt(heading.tagName().substring(1));
            toc.add(new PostDtos.TocItem(id, text, level));
        }

        String html = body.html();
        String tocJson = objectMapper.writeValueAsString(toc);
        int wordCount = countCharacters(source);
        int readingMinutes = Math.max(1, (int) Math.round(wordCount / 450.0));
        return new Rendered(html, tocJson, wordCount, readingMinutes);
    }

    /** Plain-text character count, which is the meaningful unit for Chinese prose. */
    public int countCharacters(String markdown) {
        String text = markdown
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[#>*`_~|\\-]", " ");
        return text.replaceAll("\\s", "").length();
    }

    private String uniqueId(String text, Set<String> used, int index) {
        String base = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (base.isBlank()) base = "section-" + index;
        if (base.length() > 60) base = base.substring(0, 60);
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
