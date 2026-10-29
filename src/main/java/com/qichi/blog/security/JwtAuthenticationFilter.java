package com.qichi.blog.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns a bearer token (Authorization header, or the HttpOnly cookie for plain GET navigations)
 * into an authentication, and rejects tokens whose version no longer matches the user's current
 * version (which is how a logout revokes everything at once).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final String COOKIE = AuthCookies.ACCESS;

    private final JwtService jwtService;
    private final TokenVersionService tokenVersions;
    private final String siteUrl;

    public JwtAuthenticationFilter(JwtService jwtService, TokenVersionService tokenVersions, String siteUrl) {
        this.jwtService = ${THIS_JWTSERVICE:}
        this.tokenVersions = ${THIS_TOKENVERSIONS:}
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String token = bearer(request);
        if (token == null && cookieAllowed(request)) token = fromCookie(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.parse(token);
                boolean isAccess = JwtService.TYPE_ACCESS.equals(claims.get("typ", String.class));
                long currentVersion = tokenVersions.current(Long.parseLong(claims.getSubject()));
                if (isAccess && jwtService.tokenVersion(claims) == currentVersion) {
                    LoginUser principal = jwtService.toLoginUser(claims);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ignored) {
                // an invalid or revoked token simply stays anonymous; protected routes reject it later
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) return null;
        return header.substring(PREFIX.length()).trim();
    }

    /**
     * 浏览器点链接下载（&lt;a href&gt;、文章里的 markdown 链接）走的是普通跳转，不会带
     * Authorization 头，只有 cookie 会跟着走。所以登录时把令牌同时放一份到 cookie。
     *
     * <p>只在 GET 上认它：cookie 是浏览器自动带的，若写操作也认，别的站点就能借用户的 cookie
     * 打 CSRF。GET 是只读的，而除了 /me 之外本来也全是公开接口。
     */
    /**
     * cookie 什么时候可以被当作凭证：
     *
     * <p>读请求（GET）一律认 —— 浏览器点链接、<img> 都只带 cookie。
     *
     * <p>写请求也认，但必须证明"这个请求是本站发出的"：浏览器里脚本读不到 HttpOnly cookie
     * 里的令牌，没法自己加 Authorization 头，不认 cookie 的话后台所有写操作都会 401。
     * 用 Origin 与站点地址比对代替 CSRF token —— 跨站表单带的是对方的 Origin（或没有），
     * 而同源 fetch 一定带本站 Origin。access cookie 本身也是 SameSite=Lax，双保险。
     */
    private boolean cookieAllowed(HttpServletRequest request) {
        if ("GET".equals(request.getMethod())) return true;
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return false;
        return !siteUrl.isEmpty() && siteUrl.equalsIgnoreCase(origin.replaceAll("/+$", ""));
    }

    private String fromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }
}
