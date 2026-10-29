package com.qichi.blog.common;

/**
 * 站内跳转地址校验。
 *
 * <p>只认「单个斜杠开头的站内路径」：{@code //evil.com} 是协议相对地址，{@code /\\evil.com}
 * 到了某些浏览器也会被当成协议相对地址 —— 这两个都必须拒掉，否则登录回跳就成了开放重定向。
 */
public final class SafePath {

    private static final int MAX_LENGTH = 512;

    private SafePath() {}

    public static String local(String candidate) {
        if (candidate == null) return null;
        String value = candidate.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) return null;
        if (!value.startsWith("/")) return null;
        if (value.startsWith("//") || value.startsWith("/\\")) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == '\\') return null;
        }
        return value;
    }
}
