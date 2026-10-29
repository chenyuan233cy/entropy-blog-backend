package com.qichi.blog.modules.taxonomy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class TaxonomyDtos {

    private TaxonomyDtos() {}

    @Schema(description = "分类")
    public record CategoryVO(
            Long id, String name, String slug, String description,
            String accent, Integer sortOrder, long postCount) {}

    @Schema(description = "标签")
    public record TagVO(Long id, String name, String slug, String accent, long postCount) {}

    @Schema(description = "分类新建 / 更新")
    public record CategorySaveRequest(
            @NotBlank(message = "名称不能为空") @Size(max = 50) String name,
            @Size(max = 60) String slug,
            @Size(max = 255) String description,
            @Size(max = 20) String accent,
            Integer sortOrder) {}

    @Schema(description = "标签新建 / 更新")
    public record TagSaveRequest(
            @NotBlank(message = "名称不能为空") @Size(max = 50) String name,
            @Size(max = 60) String slug,
            @Size(max = 20) String accent) {}
}
