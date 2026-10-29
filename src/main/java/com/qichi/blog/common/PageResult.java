package com.qichi.blog.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "分页结果")
public record PageResult<T>(
        @Schema(description = "当前页，从 1 开始") long page,
        @Schema(description = "每页条数") long size,
        @Schema(description = "总条数") long total,
        @Schema(description = "总页数") long pages,
        @Schema(description = "是否还有下一页") boolean hasNext,
        @Schema(description = "当前页数据") List<T> list
) {
    public static <T> PageResult<T> of(long page, long size, long total, List<T> list) {
        long pages = size <= 0 ? 0 : (total + size - 1) / size;
        return new PageResult<>(page, size, total, pages, page < pages, list);
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(page, size, 0, 0, false, List.of());
    }
}
