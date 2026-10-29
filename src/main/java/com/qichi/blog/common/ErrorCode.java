package com.qichi.blog.common;

public enum ErrorCode {
    OK(0, 200, "OK"),
    PARAM_INVALID(40001, 400, "参数校验失败"),
    BAD_REQUEST(40004, 400, "请求体格式错误"),
    UNAUTHENTICATED(40100, 401, "未提供凭证"),
    TOKEN_EXPIRED(40101, 401, "登录已过期，请刷新令牌"),
    TOKEN_INVALID(40102, 401, "凭证无效"),
    TOKEN_REVOKED(40103, 401, "凭证已失效，请重新登录"),
    FORBIDDEN(40300, 403, "没有权限执行该操作"),
    NOT_FOUND(40400, 404, "资源不存在"),
    CONFLICT(40900, 409, "资源冲突"),
    DUPLICATE(40901, 409, "重复操作"),
    RATE_LIMITED(42900, 429, "请求过于频繁，请稍后再试"),
    INTERNAL(50000, 500, "服务器内部错误"),
    UNAVAILABLE(50300, 503, "依赖服务不可用");

    private final int code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() { return code; }
    public int httpStatus() { return httpStatus; }
    public String defaultMessage() { return defaultMessage; }
}
