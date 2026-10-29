package com.qichi.blog.modules.post.controller;

import com.qichi.blog.common.PageResult;
import com.qichi.blog.common.R;
import com.qichi.blog.modules.post.PostDtos;
import com.qichi.blog.modules.post.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "搜索")
public class SearchController {

    private final PostService postService;

    public SearchController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping
    @Operation(summary = "按标题与标签模糊搜索")
    public R<PageResult<PostDtos.SearchResultVO>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        long safeSize = Math.min(Math.max(size, 1), 50);
        return R.ok(postService.search(keyword, Math.max(page, 1), safeSize));
    }
}
