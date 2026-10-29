package com.qichi.blog.security;

import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.ErrorCode;
import com.qichi.blog.common.TraceContext;
import com.qichi.blog.config.BlogProperties;
import com.qichi.blog.security.filter.RateLimitFilter;
import com.qichi.blog.security.filter.SecurityHeadersFilter;
import com.qichi.blog.security.filter.XssFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Declared purely to keep Spring Boot from generating a default in-memory user with a random
     * password. Authentication in this service happens through JWTs, never through a login form.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("本服务只接受 JWT，不支持表单登录");
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   TokenVersionService tokenVersions,
                                                   CacheService cache,
                                                   BlogProperties properties) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, tokenVersions, properties.siteUrl());
        SecurityHeadersFilter headersFilter = new SecurityHeadersFilter();
        XssFilter xssFilter = new XssFilter();
        RateLimitFilter rateLimitFilter = new RateLimitFilter(properties, cache);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        // narrow rules first: a broad permitAll would shadow them otherwise
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/register", "/api/v1/auth/email/code",
                                "/api/v1/auth/email/register",
                                // 密码登录的第二步：这时候手上还没有任何令牌，要求登录就死循环了
                                "/api/v1/auth/login/verify", "/api/v1/auth/login/code",
                                // 登出必须永远可达：访问令牌过期时也要能清掉 cookie 与刷新令牌，
                                // 否则下次打开页面会被静默登回去。没有有效凭证时它只是清 cookie。
                                "/api/v1/auth/logout").permitAll()
                        // 授权与回调是让人跳转过去的，手上不可能有令牌；解绑则必须是登录状态下发起
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/oauth/*").authenticated()
                        .requestMatchers("/api/v1/auth/oauth/**").permitAll()
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        // the reader's own account, and the admin console, are two different doors
                        .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole(LoginUser.ROLE_ADMIN)
                        // 工具页：目录谁都能浏览，下载要登录。必须排在下面那条 GET permitAll 之前
                        .requestMatchers(HttpMethod.GET, "/api/v1/tools/*/download").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
                        // commenting needs an account; liking does not
                        .requestMatchers(HttpMethod.POST, "/api/v1/comments").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/posts/*/like").permitAll()
                        .requestMatchers("/uploads/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/rss.xml", "/sitemap.xml", "/robots.txt", "/healthz", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> writeError(response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, ex) -> writeError(response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(headersFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(xssFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(BlogProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        // 空字符串也当成没填：BLOG_CORS_ALLOWED_ORIGINS= 这种写法绑出来的是一个 [""]，不是空列表
        List<String> configured = properties.corsAllowedOrigins() == null ? List.of()
                : properties.corsAllowedOrigins().stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .toList();

        // 浏览器对同源的 POST 也会带 Origin 头，所以「站点自己的源」必须放行 ——
        // 否则换到正式域名后第一个登录请求就会被这里拒成 "Invalid CORS request"。
        java.util.LinkedHashSet<String> origins = new java.util.LinkedHashSet<>(configured);
        String siteOrigin = originOf(properties.siteUrl());
        if (siteOrigin != null) origins.add(siteOrigin);

        if (origins.isEmpty()) {
            configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        } else {
            configuration.setAllowedOrigins(List.copyOf(origins));
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Visitor-Id", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining"));
        configuration.setMaxAge(3600L);
        // deliberately false: the refresh token travels in the response body, so there is no
        // cookie for a cross-site request to abuse and therefore no CSRF surface to defend
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** 把 blog.site-url 折成 scheme://host[:port]；解析不出来就返回 null。 */
    private static String originOf(String siteUrl) {
        if (siteUrl == null || siteUrl.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(siteUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            int port = uri.getPort();
            boolean defaultPort = port < 0
                    || ("http".equalsIgnoreCase(scheme) && port == 80)
                    || ("https".equalsIgnoreCase(scheme) && port == 443);
            return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + host + (defaultPort ? "" : ":" + port);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code.code()
                + ",\"message\":\"" + code.defaultMessage()
                + "\",\"data\":null,\"traceId\":\"" + TraceContext.current()
                + "\",\"timestamp\":" + System.currentTimeMillis() + "}");
    }
}
