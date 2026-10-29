package com.qichi.blog.modules.feed;

import com.qichi.blog.common.PageResult;
import com.qichi.blog.common.R;
import com.qichi.blog.config.BlogProperties;
import com.qichi.blog.modules.post.PostDtos;
import com.qichi.blog.modules.post.service.PostService;
import com.qichi.blog.modules.site.service.SiteConfigService;
import com.qichi.blog.modules.taxonomy.TaxonomyDtos;
import com.qichi.blog.modules.taxonomy.service.TaxonomyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@RestController
@Tag(name = "订阅与分享")
public class FeedController {

    // RFC 1123 requires English day and month names, so the locale is pinned
    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private final PostService postService;
    private final TaxonomyService taxonomyService;
    private final SiteConfigService configService;
    private final BlogProperties properties;
    private final OgImageService ogImageService;

    public FeedController(PostService postService, TaxonomyService taxonomyService,
                          SiteConfigService configService, BlogProperties properties,
                          OgImageService ogImageService) {
        this.postService = postService;
        this.taxonomyService = taxonomyService;
        this.configService = configService;
        this.properties = properties;
        this.ogImageService = ogImageService;
    }

    @GetMapping(value = "/rss.xml", produces = "application/rss+xml;charset=UTF-8")
    @Operation(summary = "RSS 2.0 订阅，最近 20 篇")
    public String rss() {
        String base = siteUrl();
        String siteName = configService.requireString("site.name", "");
        String description = configService.requireString("site.description", "");
        List<PostDtos.PostSummaryVO> posts = postService.list(1, 20, null, null, null, "latest").list();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n<channel>\n");
        xml.append("  <title>").append(escape(siteName)).append("</title>\n");
        xml.append("  <link>").append(escape(base)).append("</link>\n");
        xml.append("  <description>").append(escape(description)).append("</description>\n");
        xml.append("  <language>zh-CN</language>\n");
        xml.append("  <atom:link href=\"").append(escape(base)).append("/rss.xml\" rel=\"self\" type=\"application/rss+xml\"/>\n");
        if (!posts.isEmpty()) {
            xml.append("  <lastBuildDate>")
                    .append(rfc1123(posts.get(0).publishedAt() == null ? LocalDateTime.now() : posts.get(0).publishedAt()))
                    .append("</lastBuildDate>\n");
        }
        for (PostDtos.PostSummaryVO post : posts) {
            String link = base + "/posts/" + post.slug();
            xml.append("  <item>\n");
            xml.append("    <title>").append(escape(post.title())).append("</title>\n");
            xml.append("    <link>").append(escape(link)).append("</link>\n");
            xml.append("    <guid isPermaLink=\"true\">").append(escape(link)).append("</guid>\n");
            if (post.publishedAt() != null) {
                xml.append("    <pubDate>").append(rfc1123(post.publishedAt())).append("</pubDate>\n");
            }
            xml.append("    <description>").append(escape(post.summary() == null ? "" : post.summary())).append("</description>\n");
            if (post.category() != null) {
                xml.append("    <category>").append(escape(post.category().name())).append("</category>\n");
            }
            for (PostDtos.TagBrief tag : post.tags()) {
                xml.append("    <category>").append(escape(tag.name())).append("</category>\n");
            }
            xml.append("  </item>\n");
        }
        xml.append("</channel>\n</rss>\n");
        return xml.toString();
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    @Operation(summary = "站点地图")
    public String sitemap() {
        String base = siteUrl();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (String path : List.of("/", "/posts", "/archive", "/about", "/music", "/search")) {
            xml.append("  <url><loc>").append(escape(base + path)).append("</loc></url>\n");
        }
        for (PostDtos.PostSummaryVO post : postService.list(1, 50, null, null, null, "latest").list()) {
            xml.append("  <url><loc>").append(escape(base + "/posts/" + post.slug())).append("</loc>");
            if (post.publishedAt() != null) {
                xml.append("<lastmod>").append(post.publishedAt().toLocalDate()).append("</lastmod>");
            }
            xml.append("</url>\n");
        }
        for (TaxonomyDtos.TagVO tag : taxonomyService.listTags()) {
            xml.append("  <url><loc>").append(escape(base + "/tags/" + tag.slug())).append("</loc></url>\n");
        }
        for (TaxonomyDtos.CategoryVO category : taxonomyService.listCategories()) {
            xml.append("  <url><loc>").append(escape(base + "/posts?category=" + category.slug())).append("</loc></url>\n");
        }
        xml.append("</urlset>\n");
        return xml.toString();
    }

    @GetMapping(value = "/api/v1/og/{slug}.png", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "动态分享卡片，Java2D 绘制，可正确渲染中文标题")
    public ResponseEntity<byte[]> ogImage(@PathVariable String slug) {
        PostDtos.PostDetailVO detail = postService.detail(slug, null);
        byte[] png = ogImageService.render(
                detail,
                configService.requireString("site.name", ""),
                configService.authorNickname(),
                detail.category() == null ? null : detail.category().accent());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePublic())
                .body(png);
    }

    @GetMapping(value = "/api/v1/og/post/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "动态分享卡片所需的元数据")
    public R<OgMeta> ogMeta(@PathVariable String slug) {
        PostDtos.PostDetailVO detail = postService.detail(slug, null);
        return R.ok(new OgMeta(
                detail.title(),
                detail.summary(),
                detail.category() == null ? null : detail.category().name(),
                detail.category() == null ? null : detail.category().accent(),
                detail.publishedAt(),
                detail.readingMinutes(),
                detail.coverUrl(),
                configService.authorNickname(),
                configService.requireString("site.name", "")));
    }

    public record OgMeta(String title, String summary, String category, String accent,
                         LocalDateTime publishedAt, int readingMinutes, String coverUrl,
                         String author, String siteName) {}

    /** RFC 1123 needs an offset, and a LocalDateTime has none, so it is anchored to the JVM zone. */
    private String rfc1123(LocalDateTime value) {
        return RFC_1123.format(value.atZone(ZoneId.systemDefault()));
    }

    private String siteUrl() {
        String url = properties.siteUrl();
        if (url == null || url.isBlank()) url = "http://localhost:3000";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
