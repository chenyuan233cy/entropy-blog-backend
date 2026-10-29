package com.qichi.blog.modules.comment.controller;

import com.qichi.blog.common.PageResult;
import com.qichi.blog.common.R;
import com.qichi.blog.modules.comment.CommentDtos;
import com.qichi.blog.modules.comment.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/comments")
@Tag(name = "后台 · 评论审核")
public class AdminCommentController {

    private final CommentService commentService;

    public AdminCommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    @Operation(summary = "评论列表，status=all 表示全部；reported=true 只看被举报的")
    public R<PageResult<CommentDtos.AdminCommentVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean reported) {
        return R.ok(commentService.adminList(Math.max(page, 1), Math.min(Math.max(size, 1), 50), status, keyword, reported));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "通过 / 驳回 / 标记垃圾")
    public R<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody CommentDtos.CommentStatusRequest request) {
        commentService.changeStatus(id, request.status());
        return R.ok();
    }

    @PostMapping("/{id}/reply")
    @Operation(summary = "以作者身份回复，直接通过审核")
    public R<CommentDtos.CommentVO> reply(@PathVariable Long id, @Valid @RequestBody CommentDtos.CommentReplyRequest request) {
        return R.ok(commentService.adminReply(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除评论及其回复")
    public R<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return R.ok();
    }
}
