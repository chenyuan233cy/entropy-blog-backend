package com.qichi.blog.modules.site;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class SiteDtos {

    private SiteDtos() {}

    @Schema(description = "一条站点配置")
    public record ConfigItemVO(
            String key, String value, String valueType, String groupName, String description) {}

    @Schema(description = "按分组聚合的配置")
    public record ConfigGroupVO(String group, List<ConfigItemVO> items) {}

    @Schema(description = "批量更新配置")
    public record ConfigUpdateRequest(List<Item> items) {
        public record Item(@NotBlank String key, String value) {}
    }

    @Schema(description = "站点统计")
    public record SiteStatsVO(
            long postCount, long draftCount, long tagCount, long categoryCount,
            long commentCount, long pendingCommentCount, long trackCount,
            long totalWords, java.time.LocalDateTime startAt) {}
}
