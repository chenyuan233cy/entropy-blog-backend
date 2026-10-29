package com.qichi.blog.modules.post;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class PostDtos {

    private PostDtos() {}

    @Schema(description = "写作时的即时预览请求")
    public record PreviewRequest(String markdown) {}

    @Schema(description = "写作时的即时预览结果")
    public record PreviewVO(String contentHtml, int wordCount, int readingMinutes) {}

    @Schema(description = "分类摘要")
    public record CategoryBrief(Long id, String name, String slug, String accent) {}

    @Schema(description = "标签摘要")
    public record TagBrief(Long id, String name, String slug, String accent) {}

    @Schema(description = "目录锚点")
    public record TocItem(String id, String text, int level) {}

    @Schema(description = "列表项")
    public record PostSummaryVO(
            Long id, String title, String slug, String summary, String coverUrl,
            CategoryBrief category, List<TagBrief> tags,
            long viewCount, long likeCount, int commentCount, int readingMinutes,
            boolean top, LocalDateTime publishedAt) {}

    @Schema(description = "搜索命中项")
    public record SearchResultVO(PostSummaryVO post, List<String> matchedTags) {}

    @Schema(description = "相邻文章")
    public record AdjacentItem(String title, String slug) {}

    @Schema(description = "上一 / 下一篇")
    public record AdjacentVO(AdjacentItem previous, AdjacentItem next) {}

    @Schema(description = "详情")
    public record PostDetailVO(
            Long id, String title, String slug, String summary, String coverUrl,
            String contentHtml, List<TocItem> toc,
            CategoryBrief category, List<TagBrief> tags,
            long viewCount, long likeCount, int commentCount, int readingMinutes, int wordCount,
            boolean top, boolean allowComment, LocalDateTime publishedAt, LocalDateTime updateTime,
            AdjacentVO adjacent, List<PostSummaryVO> related,
            String seoTitle, String seoDescription) {}

    @Schema(description = "归档项")
    public record ArchiveItem(Long id, String title, String slug, String summary, LocalDateTime publishedAt) {}

    @Schema(description = "按年归档")
    public record ArchiveGroupVO(int year, long count, List<ArchiveItem> posts) {}

    @Schema(description = "点赞结果")
    public record LikeResult(boolean liked, long likeCount) {}

    @Schema(description = "后台列表项")
    public record AdminPostVO(
            Long id, String title, String slug, String status, String categoryName,
            List<String> tagNames, long viewCount, int commentCount, int readingMinutes,
            boolean top, LocalDateTime publishedAt, LocalDateTime updateTime) {}

    @Schema(description = "后台编辑详情")
    public record AdminPostDetailVO(
            Long id, String title, String slug, String summary, String coverUrl, String contentMd,
            Long categoryId, List<Long> tagIds, String status, boolean top, boolean allowComment,
            String seoTitle, String seoDescription, LocalDateTime publishedAt,
            LocalDateTime createTime, LocalDateTime updateTime) {}

    @Schema(description = "文章新建 / 更新")
    public record PostSaveRequest(
            @NotBlank(message = "标题不能为空") @Size(max = 200) String title,
            @Size(max = 200) String slug,
            @Size(max = 500) String summary,
            @Size(max = 500) String coverUrl,
            @NotBlank(message = "正文不能为空") String contentMd,
            Long categoryId,
            List<Long> tagIds,
            @Size(max = 16) String status,
            Boolean top,
            Boolean allowComment,
            @Size(max = 200) String seoTitle,
            @Size(max = 300) String seoDescription,
            LocalDateTime publishedAt) {}

    @Schema(description = "状态切换")
    public record StatusPatchRequest(@NotBlank String status) {}

    @Schema(description = "置顶切换")
    public record TopPatchRequest(boolean top) {}
}
