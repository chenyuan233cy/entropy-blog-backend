package com.qichi.blog.security;

import com.qichi.blog.config.BlogProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * 会话 cookie 的唯一出口。
 *
 * <p>两个令牌都只走 HttpOnly cookie，前端拿不到、脚本偷不走：
 * <ul>
 *   <li>{@code qichi-access}：SameSite=Lax，Path=/。浏览器的普通跳转（点链接下载）只带 cookie
 *       不带 Authorization 头，所以下载鉴权靠它；Lax 保证跨站发起的 POST 带不上它，免掉 CSRF 面。</li>
 *   <li>{@code qichi-refresh}：SameSite=Strict，Path=/api/v1/auth —— 只有刷新和登出这两个接口
 *       能收到它，别的地方连发都不发。</li>
 * </ul>
 */
public final class AuthCookies {

    public static final String ACCESS = "qichi-access";
    public static final String REFRESH = "qichi-refresh";

    private static final String ACCESS_PATH = "/";
    private static final String REFRESH_PATH = "/api/v1/auth";

    private AuthCookies() {}

    public static void write(HttpServletResponse response, BlogProperties properties,
                             String accessToken, String refreshToken) {
        boolean secure = properties.cookieSecure();
        response.addHeader(HttpHeaders.SET_COOKIE, build(ACCESS, accessToken, secure, ACCESS_PATH, "Lax",
                properties.jwt().accessTtl()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, build(REFRESH, refreshToken, secure, REFRESH_PATH, "Strict",
                properties.jwt().refreshTtl()).toString());
    }

    public static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(ACCESS, "", secure, ACCESS_PATH, "Lax", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, build(REFRESH, "", secure, REFRESH_PATH, "Strict", Duration.ZERO).toString());
    }

    public static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    private static ResponseCookie build(String name, String value, boolean secure,
                                        String path, String sameSite, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .path(path)
                .sameSite(sameSite)
                .maxAge(maxAge)
                .build();
    }
}
