package com.qichi.blog.modules.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class CommentDtos {

    private CommentDtos() {}

    @Schema(description = "评论。status 只在刚发表时对本人有意义：列表里全都是已通过的")
    public record CommentVO(
            Long id, Long parentId, String replyToName, String nickname, String avatar, String website,
            String content, int likeCount, boolean admin, LocalDateTime createdAt,
            String status, int reportCount,
            /** 是不是当前登录的人自己写的：前端据此显示「删除」而不是「举报」 */
            boolean mine,
            List<CommentVO> replies) {}

    @Schema(description = "后台评论条目，额外带上所属文章")
    public record AdminCommentVO(
            Long id, Long postId, String postTitle, Long parentId, String replyToName,
            String nickname, String email, String website, String content,
            String status, boolean admin, LocalDateTime createdAt, int reportCount) {}

    @Schema(description = "发表评论。昵称、邮箱、头像、网站一律取自登录账号，请求里传不了")
    public record CommentCreateRequest(
            @NotNull(message = "postId 不能为空") Long postId,
            Long parentId,
            @NotBlank(message = "内容不能为空")
            @Size(min = 2, max = 1000, message = "内容 2 到 1000 字") String content) {}

    @Schema(description = "侧栏用的最新评论")
    public record LatestCommentVO(
            Long id, String nickname, String avatar, String content,
            String postSlug, String postTitle, LocalDateTime createdAt) {}

    @Schema(description = "审核状态变更")
    public record CommentStatusRequest(@NotBlank String status) {}

    @Schema(description = "管理员回复")
    public record CommentReplyRequest(
            @NotBlank(message = "回复内容不能为空") @Size(min = 1, max = 1000) String content) {}
}
