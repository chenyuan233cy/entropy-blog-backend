package com.qichi.blog.modules.sponsor;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class SponsorDtos {

    private SponsorDtos() {}

    @Schema(description = "赞助者。amount 为 0 表示对方不想露数额")
    public record SponsorVO(
            Long id, String name, BigDecimal amount, String message,
            String avatarUrl, String link, LocalDate sponsoredAt,
            boolean visible, int sortOrder) {}

    @Schema(description = "一条赞助方式。kind 决定 content 怎么解释")
    public record SponsorMethodVO(
            Long id, String name, String kind, String content, int sortOrder) {}

    @Schema(description = "新增 / 修改赞助方式")
    public record SponsorMethodRequest(
            @NotBlank(message = "总得有个名称") @Size(max = 60, message = "名称太长了") String name,
            @NotBlank(message = "请选择类型") String kind,
            @NotBlank(message = "内容不能为空") @Size(max = 1000, message = "内容太长了") String content,
            Integer sortOrder) {}

    @Schema(description = "新增 / 修改赞助者")
    public record SponsorRequest(
            @NotBlank(message = "总得有个称呼") @Size(max = 60, message = "称呼太长了") String name,
            BigDecimal amount,
            @Size(max = 300, message = "留言最多 300 字") String message,
            String avatarUrl,
            String link,
            LocalDate sponsoredAt,
            Boolean visible,
            Integer sortOrder) {}
}
