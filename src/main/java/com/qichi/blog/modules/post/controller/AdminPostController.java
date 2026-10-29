package com.qichi.blog.modules.post.controller;

import com.qichi.blog.common.PageResult;
import com.qichi.blog.common.R;
import com.qichi.blog.modules.post.PostDtos;
import com.qichi.blog.modules.post.service.MarkdownService;
import com.qichi.blog.modules.post.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/posts")
@Tag(name = "后台 · 文章")
public class AdminPostController {

    private final PostService postService;
    private final MarkdownService markdownService;

    public AdminPostController(PostService postService, MarkdownService markdownService) {
        this.postService = postService;
        this.markdownService = markdownService;
    }

    @GetMapping
    @Operation(summary = "含草稿的文章列表")
    public R<PageResult<PostDtos.AdminPostVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return R.ok(postService.adminList(Math.max(page, 1), Math.min(Math.max(size, 1), 50), status, keyword));
    }

    @GetMapping("/{id}")
    @Operation(summary = "编辑用详情，含 markdown 原文")
    public R<PostDtos.AdminPostDetailVO> detail(@PathVariable Long id) {
        return R.ok(postService.adminDetail(id));
    }

    @PostMapping
    @Operation(summary = "新建文章")
    public R<Long> create(@Valid @RequestBody PostDtos.PostSaveRequest request) {
        return R.ok(postService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新文章")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PostDtos.PostSaveRequest request) {
        postService.update(id, request);
        return R.ok();
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "发布 / 转草稿")
    public R<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody PostDtos.StatusPatchRequest request) {
        postService.changeStatus(id, request.status());
        return R.ok();
    }

    @PatchMapping("/{id}/top")
    @Operation(summary = "置顶切换")
    public R<Void> toggleTop(@PathVariable Long id, @RequestBody PostDtos.TopPatchRequest request) {
        postService.toggleTop(id, request.top());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文章")
    public R<Void> delete(@PathVariable Long id) {
        postService.delete(id);
        return R.ok();
    }

    @PostMapping("/preview")
    @Operation(summary = "按同一套渲染规则即时渲染 Markdown，写作时用来做实时预览")
    public R<PostDtos.PreviewVO> preview(@RequestBody PostDtos.PreviewRequest request) {
        MarkdownService.Rendered rendered = markdownService.render(request.markdown() == null ? "" : request.markdown());
        return R.ok(new PostDtos.PreviewVO(rendered.html(), rendered.wordCount(), rendered.readingMinutes()));
    }
}
