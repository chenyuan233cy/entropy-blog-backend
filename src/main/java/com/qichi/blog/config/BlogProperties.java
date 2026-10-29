package com.qichi.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "blog")
public record BlogProperties(
        String siteUrl,
        List<String> corsAllowedOrigins,
        /**
         * 只有反向代理会**覆盖** X-Forwarded-For / X-Real-IP 时才允许打开。
         * 打开表示相信代理塞进来的客户端地址；关着一律用 TCP 对端地址。
         */
        boolean trustForwardedHeaders,
        /** 会话 cookie 是否带 Secure。生产 HTTPS 必须 true，本地 http 开发必须 false。 */
        boolean cookieSecure,
        Jwt jwt,
        Upload upload,
        RateLimit rateLimit,
        Oauth oauth,
        Mail mail,
        /** 公开注册开关：单作者站点默认关闭，首次初始化管理员时临时打开。 */
        Auth auth
) {

    public record Auth(boolean registrationEnabled) {}
    public record Jwt(String secret, String issuer, Duration accessTtl, Duration refreshTtl, Duration refreshGrace) {}

    /**
     * 注册验证码的发信配置。enabled=false 或者账号没填时，接口会明确说「还没配置」，
     * 而不是假装发出去让用户干等。
     */
    public record Mail(boolean enabled, String host, int port, boolean ssl, boolean starttls,
                       String username, String password, String from, String fromName,
                       Duration codeTtl, Duration resendInterval) {

        /**
         * 没填账号也算配好了 —— 那就是一台不做认证的中继（本地 Mailpit / smtp4dev 就是这样）。
         * 但填了账号却没填密码，是不完整的配置，直接判为没配好，免得发出一个必然 535 的请求。
         */
        public boolean configured() {
            if (!enabled || host == null || host.isBlank()) return false;
            boolean hasUser = username != null && !username.isBlank();
            boolean hasPass = password != null && !password.isBlank();
            // 没有账号只对“不认证中继”（本地 Mailpit 之类）成立；对外部 SMTP 必须填账号，
            // 否则本地会拿空账号去连 smtp.qq.com，然后报一个看不懂的发信失败。
            if (!hasUser) return "localhost".equals(host) || "127.0.0.1".equals(host);
            return hasPass;
        }

        /** 没单独填发件人就跟着登录账号走。 */
        public String sender() {
            return from == null || from.isBlank() ? username : from;
        }
    }

    public record Upload(String dir, long maxBytes, List<String> allowedExtensions,
                         long fileMaxBytes, List<String> fileBlockedExtensions) {

        /**
         * 工具页的文件上传：除了这些后缀，别的都能传。
         *
         * <p>挡的是「浏览器在自己的域名下会执行」的类型 —— 传个 .html 上来就等于给了我一份
         * 同源的 XSS 页面，.svg 能夹脚本，.js / .mjs 能被别的页面 import。那已经不是分享文件，
         * 是往站点里塞代码。
         */
        public boolean fileAllowed(String extension) {
            if (extension == null || extension.isBlank()) return true;
            List<String> blocked = fileBlockedExtensions();
            return blocked == null || !blocked.contains(extension.toLowerCase(java.util.Locale.ROOT));
        }
    }

    public record RateLimit(boolean enabled, List<Rule> rules) {
        public record Rule(String name, String pattern, int limit, Duration window) {}
    }

    public record Oauth(String githubClientId, String githubClientSecret, String githubRedirectUri,
                              String googleClientId, String googleClientSecret, String googleRedirectUri) {
        public boolean configured() {
            return githubClientId != null && !githubClientId.isBlank()
                    && githubClientSecret != null && !githubClientSecret.isBlank();
        }
    }
}
