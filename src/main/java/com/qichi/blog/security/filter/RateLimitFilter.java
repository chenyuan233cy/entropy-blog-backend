package com.qichi.blog.security.filter;

import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.ErrorCode;
import com.qichi.blog.common.TraceContext;
import com.qichi.blog.config.BlogProperties;
import com.qichi.blog.security.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-window rate limiting backed by Redis, with a per-route bucket and a per-caller identity.
 *
 * <p>Rules are evaluated in declaration order and the first match wins, so narrow patterns must be
 * listed before broad ones.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "blog:rate:";

    private final BlogProperties properties;
    private final CacheService cache;
    private final boolean trustForwarded;
    private final List<CompiledRule> rules;

    public RateLimitFilter(BlogProperties properties, CacheService cache) {
        this.properties = properties;
        this.cache = cache;
        this.trustForwarded = properties.trustForwardedHeaders();
        this.rules = new ArrayList<>();
        BlogProperties.RateLimit config = properties.rateLimit();
        if (config != null && config.rules() != null) {
            for (BlogProperties.RateLimit.Rule rule : config.rules()) {
                rules.add(new CompiledRule(rule.name(), rule.pattern(), rule.limit(), rule.window()));
            }
        }
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        BlogProperties.RateLimit config = properties.rateLimit();
        if (config == null || !config.enabled() || rules.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        CompiledRule rule = match(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        long windowSeconds = Math.max(1L, rule.window().toSeconds());
        long bucket = System.currentTimeMillis() / 1000L / windowSeconds;
        String key = KEY_PREFIX + rule.name() + ":" + bucket + ":" + identity(request);

        cache.setIfAbsent(key, "0", Duration.ofSeconds(windowSeconds + 1));
        long used = cache.increment(key, 1L);

        response.setHeader("X-RateLimit-Limit", Integer.toString(rule.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0L, rule.limit() - used)));

        if (used > rule.limit()) {
            response.setHeader("Retry-After", Long.toString(windowSeconds));
            writeTooManyRequests(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private CompiledRule match(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        for (CompiledRule rule : rules) {
            if (rule.matches(method, path)) return rule;
        }
        return null;
    }

    private String identity(HttpServletRequest request) {
        return SecurityUtils.currentUser()
                .map(user -> "u" + user.id())
                .orElseGet(() -> "ip" + hash(clientIp(request)));
    }

    /**
     * 客户端地址。
     *
     * <p>默认只用 TCP 对端地址：转发头是客户端可以随便写的，谁都能靠伪造一个
     * {@code X-Forwarded-For} 换一个限流桶，等于没有限流。只有确认反向代理会**覆盖**
     * 这两个头时才允许打开 {@code blog.trust-forwarded-headers}。
     */
    private String clientIp(HttpServletRequest request) {
        if (trustForwarded) {
            // Nginx 的 proxy_set_header X-Real-IP $remote_addr 会覆盖客户端自带的同名头
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) return realIp.trim();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", bytes[i]));
            return sb.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.RATE_LIMITED.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + ErrorCode.RATE_LIMITED.code()
                + ",\"message\":\"" + ErrorCode.RATE_LIMITED.defaultMessage()
                + "\",\"data\":null,\"traceId\":\"" + TraceContext.current()
                + "\",\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    private record CompiledRule(String name, String pattern, int limit, Duration window) {

        /**
         * 「方法 + 路径」逐段匹配：{@code *} 匹配一段，{@code **} 匹配剩下的全部。
         * 中间段的通配也要能生效（{@code /api/v1/posts/*&#47;like} 就是靠它）。
         */
        boolean matches(String method, String path) {
            String expectedMethod = null;
            String expectedPath = pattern == null ? "" : pattern.trim();
            int space = expectedPath.indexOf(' ');
            if (space > 0) {
                expectedMethod = expectedPath.substring(0, space).toUpperCase();
                expectedPath = expectedPath.substring(space + 1).trim();
            }
            if (expectedMethod != null && !expectedMethod.equals(method)) return false;
            if (expectedPath.isEmpty()) return false;

            String[] expected = expectedPath.split("/", -1);
            String[] actual = path.split("/", -1);
            for (int i = 0; i < expected.length; i++) {
                if ("**".equals(expected[i])) return true;
                if (i >= actual.length) return false;
                if (!"*".equals(expected[i]) && !expected[i].equals(actual[i])) return false;
            }
            return expected.length == actual.length;
        }
    }
}
