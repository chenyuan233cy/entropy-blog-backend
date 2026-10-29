package com.qichi.blog.modules.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class ToolDtos {

    private ToolDtos() {}

    @Schema(description = "工具页的一个条目，文件夹或文件")
    public record ToolNodeVO(
            Long id, Long parentId, String kind, String name, String url,
            long size, String mime, String ext, String description,
            int downloadCount, boolean hasChildren, LocalDateTime createTime) {}

    @Schema(description = "面包屑的一节")
    public record Crumb(Long id, String name) {}

    @Schema(description = "某一层目录的内容")
    public record Listing(List<Crumb> path, List<ToolNodeVO> nodes, long folderCount, long fileCount, long totalSize) {}

    @Schema(description = "新建文件夹")
    public record FolderRequest(
            @NotBlank(message = "文件夹得有名字") @Size(max = 100, message = "名字太长了") String name,
            Long parentId) {}

    @Schema(description = "改名 / 改说明")
    public record RenameRequest(
            @NotBlank(message = "名字不能为空") @Size(max = 200, message = "名字太长了") String name,
            @Size(max = 300, message = "说明最多 300 字") String description) {}
}
