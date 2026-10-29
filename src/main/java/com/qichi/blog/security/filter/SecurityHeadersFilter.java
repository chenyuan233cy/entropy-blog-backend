package com.qichi.blog.security.filter;

import com.qichi.blog.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Assigns a trace id to every request and sets the hardening response headers.
 * Registered inside the Spring Security chain, not as a container filter bean.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "object-src 'none'",
            "img-src 'self' data:",
            "font-src 'self' data:",
            "style-src 'self' 'unsafe-inline'",
            "script-src 'self' 'unsafe-inline'",
            "connect-src 'self'");

    /**
     * 上传目录的响应单独收紧：那里放的是用户传上来的字节，万一混进一个 .html / .svg，
     * 这条 CSP 会让它即便被执行也没有任何权限（sandbox 叠加 default-src 'none'）。
     */
    private static final String UPLOAD_CSP =
            "default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; sandbox";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        TraceContext.set(TraceContext.generate());
        response.setHeader("X-Request-Id", TraceContext.current());
        response.setHeader("Content-Security-Policy",
                request.getRequestURI().startsWith("/uploads/") ? UPLOAD_CSP : CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), interest-cohort=()");
        response.setHeader("Cross-Origin-Resource-Policy", "same-site");
        try {
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
