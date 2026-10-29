package com.qichi.blog.security;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Input hardening.
 *
 * <p>Markdown renders to HTML on the server, so the only place untrusted markup can appear is the
 * article body. That HTML is cleaned against a strict allowlist before it is stored, which means
 * the database never holds a script tag. Everything else in the system is plain text and is
 * additionally stripped of angle brackets on the way in.
 */
@Component
public class HtmlSanitizer {

    private final Safelist safelist = Safelist.relaxed()
            .addTags("figure", "figcaption", "del", "ins", "mark", "hr", "span", "div", "input", "kbd", "sup", "sub")
            .addAttributes(":all", "id", "class", "title")
            .addAttributes("a", "href", "target", "rel")
            .addAttributes("img", "src", "alt", "width", "height", "loading")
            .addAttributes("input", "type", "checked", "disabled")
            .addAttributes("code", "class")
            .addAttributes("th", "align")
            .addAttributes("td", "align")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(true);


    /**
     * 清洗时用的假基准地址。jsoup 的协议白名单只认绝对地址，而正文里的图片和站内链接都是
     * 相对写法（/uploads/x.png、/posts/y）—— 基准为空时它们过不了校验，整条属性会被删掉，
     * 表现就是文章里的图一个都不显示。给个基准先解析成绝对地址通过校验，
     * preserveRelativeLinks 再保证写回数据库的仍然是原来的相对写法。
     */
    private static final String BASE_URI = "https://qichi.invalid/";

    public String cleanHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.clean(html, BASE_URI, safelist, new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
    }

    /** Strips every tag and control character. Used for comments, nicknames, config values. */
    public String plain(String value) {
        if (value == null) return null;
        String withoutTags = Jsoup.clean(value, Safelist.none());
        String normalized = withoutTags.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return normalized;
    }

    /** Removes angle brackets only, keeping the rest of the text intact. */
    public String stripBrackets(String value) {
        if (value == null) return null;
        return value.replace("<", "").replace(">", "");
    }
}
