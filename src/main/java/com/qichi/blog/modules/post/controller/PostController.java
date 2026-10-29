package com.qichi.blog.modules.post.controller;

import com.qichi.blog.common.PageResult;
import com.qichi.blog.common.R;
import com.qichi.blog.modules.post.PostDtos;
import com.qichi.blog.modules.post.entity.Post;
import com.qichi.blog.modules.post.service.InteractionService;
import com.qichi.blog.modules.post.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/posts")
@Tag(name = "文章", description = "公开的文章读取接口")
public class PostController {

    private final PostService postService;
    private final InteractionService interaction;

    public PostController(PostService postService, InteractionService interaction) {
        this.postService = postService;
        this.interaction = interaction;
    }

    @GetMapping
    @Operation(summary = "文章列表，支持标签 / 分类 / 年份筛选与排序")
    public R<PageResult<PostDtos.PostSummaryVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "8") long size,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "latest") String sort) {
        long safeSize = Math.min(Math.max(size, 1), 50);
        return R.ok(postService.list(Math.max(page, 1), safeSize, tag, category, year, sort));
    }

    @GetMapping("/featured")
    @Operation(summary = "首页精选")
    public R<List<PostDtos.PostSummaryVO>> featured(@RequestParam(defaultValue = "4") int limit) {
        return R.ok(postService.featured(Math.min(Math.max(limit, 1), 12)));
    }

    @GetMapping("/archives")
    @Operation(summary = "按年份归档")
    public R<List<PostDtos.ArchiveGroupVO>> archives() {
        return R.ok(postService.archives());
    }

    @GetMapping("/{slug}")
    @Operation(summary = "文章详情，同访客十分钟内只计一次阅读")
    public R<PostDtos.PostDetailVO> detail(@PathVariable String slug,
                                           @RequestHeader(value = "X-Visitor-Id", required = false) String visitorId,
                                           HttpServletRequest request) {
        return R.ok(postService.detail(slug, visitorId(visitorId, request)));
    }

    @GetMapping("/{slug}/adjacent")
    @Operation(summary = "上一篇 / 下一篇")
    public R<PostDtos.AdjacentVO> adjacent(@PathVariable String slug) {
        return R.ok(postService.adjacent(slug));
    }

    @PostMapping("/{slug}/like")
    @Operation(summary = "点赞，同一访客只计一次")
    public R<PostDtos.LikeResult> like(@PathVariable String slug,
                                       @RequestHeader(value = "X-Visitor-Id", required = false) String visitorId,
                                       HttpServletRequest request) {
        Post post = postService.requirePublishedBySlug(slug);
        return R.ok(interaction.like(post.getId(), visitorId(visitorId, request)));
    }

    /** Falls back to a hash of the caller address when the browser sends no visitor id. */
    private String visitorId(String provided, HttpServletRequest request) {
        if (provided != null && !provided.isBlank()) return provided.trim();
        String ip = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", bytes[i]));
            return "anon-" + sb;
        } catch (Exception ex) {
            return "anon";
        }
    }
}
