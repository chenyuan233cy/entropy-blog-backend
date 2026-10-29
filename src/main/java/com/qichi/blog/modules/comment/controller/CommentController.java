package com.qichi.blog.modules.comment.controller;

import com.qichi.blog.common.BizException;
import com.qichi.blog.common.PageResult;
import com.qichi.blog.common.R;
import com.qichi.blog.modules.comment.CommentDtos;
import com.qichi.blog.modules.comment.service.CommentService;
import com.qichi.blog.modules.post.entity.Post;
import com.qichi.blog.modules.post.service.PostService;
import com.qichi.blog.security.LoginUser;
import com.qichi.blog.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/comments")
@Tag(name = "评论")
public class CommentController {

    private final CommentService commentService;
    private final PostService postService;

    public CommentController(CommentService commentService, PostService postService) {
        this.commentService = commentService;
        this.postService = postService;
    }

    @GetMapping
    @Operation(summary = "某篇文章的已通过评论，两层结构")
    public R<PageResult<CommentDtos.CommentVO>> list(
            @RequestParam String postSlug,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Post post = postService.requirePublishedBySlug(postSlug);
        // 游客也能看；登录了就把「哪几条是我写的」一起标出来
        Long viewerId = SecurityUtils.currentUser().map(LoginUser::id).orElse(null);
        return R.ok(commentService.listForPost(post.getId(), Math.max(page, 1), Math.min(Math.max(size, 1), 50), viewerId));
    }

    @GetMapping("/latest")
    @Operation(summary = "最新的已通过评论，供侧栏使用")
    public R<List<CommentDtos.LatestCommentVO>> latest(@RequestParam(defaultValue = "5") int limit) {
        return R.ok(commentService.latest(limit));
    }

    @PostMapping("/{id}/report")
    @Operation(summary = "举报一条评论，需要登录。只累加计数，是否违规由站长判断")
    public R<Void> report(@PathVariable Long id) {
        commentService.report(id, SecurityUtils.requireUser());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删掉自己发的评论（站长可以删任何一条）")
    public R<Void> remove(@PathVariable Long id) {
        commentService.deleteOwn(id, SecurityUtils.requireUser());
        return R.ok();
    }

    @PostMapping
    @Operation(summary = "发表评论，需要登录。默认直接发布，命中屏蔽词才转人工")
    public R<CommentDtos.CommentVO> create(@Valid @RequestBody CommentDtos.CommentCreateRequest request,
                                           HttpServletRequest http) {
        if (request.postId() == null) throw BizException.invalid("缺少 postId");
        return R.ok(commentService.create(request, SecurityUtils.requireUser(), http));
    }
}
