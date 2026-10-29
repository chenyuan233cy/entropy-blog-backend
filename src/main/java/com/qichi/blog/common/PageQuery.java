package com.qichi.blog.common;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "分页参数")
public record PageQuery(
        @Schema(description = "页码，从 1 开始", defaultValue = "1") Integer page,
        @Schema(description = "每页条数，上限 50", defaultValue = "8") Integer size
) {
    public static final int MAX_SIZE = 50;

    public long safePage() {
        return page == null || page < 1 ? 1L : page;
    }

    public long safeSize() {
        if (size == null || size < 1) return 8L;
        return Math.min(size, MAX_SIZE);
    }
}
