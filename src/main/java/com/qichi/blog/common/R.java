package com.qichi.blog.common;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "统一响应体")
public record R<T>(
        @Schema(description = "业务码，0 表示成功") int code,
        @Schema(description = "提示信息") String message,
        @Schema(description = "业务数据") T data,
        @Schema(description = "追踪 id") String traceId,
        @Schema(description = "毫秒时间戳") long timestamp
) {
    public static <T> R<T> ok(T data) {
        return new R<>(0, "OK", data, TraceContext.current(), System.currentTimeMillis());
    }

    public static R<Void> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.defaultMessage());
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return new R<>(errorCode.code(), message, null, TraceContext.current(), System.currentTimeMillis());
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message, T data) {
        return new R<>(errorCode.code(), message, data, TraceContext.current(), System.currentTimeMillis());
    }
}
