package com.qichi.blog.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.modules.site.entity.SiteConfig;
import com.qichi.blog.modules.site.mapper.SiteConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动自检：把「配置忘了改」从线上事故变成一条启动日志（生产 profile 下直接拒绝启动）。
 *
 * <p>这里只查那些「不改也没报错，但一上线就出人命」的项，别的配置项错了自然会表现为功能异常。
 */
@Component
@Order(100)
public class StartupSecurityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityCheck.class);

    /** 弱密钥特征：显式的占位词，命中即视为「忘了改」。 */
    private static final List<String> WEAK_SECRET_MARKERS = List.of(
            "change-me", "change_me", "changeme", "example", "your-", "your_", "password", "secret");

    /** 建议的最小随机长度；只告警，不阻断启动，避免误伤已有的短密钥。 */
    private static final int RECOMMENDED_SECRET_LENGTH = 32;

    private final BlogProperties properties;
    private final Environment environment;
    private final SiteConfigMapper configMapper;

    public StartupSecurityCheck(BlogProperties properties, Environment environment, SiteConfigMapper configMapper) {
        this.properties = properties;
        this.environment = environment;
        this.configMapper = configMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean prod = List.of(environment.getActiveProfiles()).contains("prod");
        List<String> fatal = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String jwtSecret = properties.jwt() == null ? null : properties.jwt().secret();
        if (isUnset(jwtSecret) || hasWeakMarker(jwtSecret)) {
            String message = "jwt.secret 仍是默认/占位值（为空或含 " + WEAK_SECRET_MARKERS + " 之一），任何拿到仓库的人都能伪造令牌。生产请设置 BLOG_JWT_SECRET。";
            fatal.add(message);
            warnings.add(message);
        } else if (jwtSecret.trim().length() < RECOMMENDED_SECRET_LENGTH) {
            warnings.add("jwt.secret 仅 " + jwtSecret.trim().length() + " 位，建议 32 位以上随机串。");
        }

        String dbPassword = environment.getProperty("spring.datasource.password");
        if (isUnset(dbPassword) || hasWeakMarker(dbPassword)) {
            String message = "数据库密码仍是默认/占位值（为空或含 " + WEAK_SECRET_MARKERS + " 之一），生产请设置 SPRING_DATASOURCE_PASSWORD。";
            fatal.add(message);
            warnings.add(message);
        } else if (dbPassword.trim().length() < RECOMMENDED_SECRET_LENGTH / 2) {
            warnings.add("数据库密码仅 " + dbPassword.trim().length() + " 位，建议再长一些。");
        }

        if (properties.upload() == null || properties.upload().fileBlockedExtensions() == null
                || properties.upload().fileBlockedExtensions().isEmpty()) {
            String message = "blog.upload.file-blocked-extensions 为空：上传的 .html/.svg 会以本站身份执行脚本。";
            fatal.add(message);
            warnings.add(message);
        }

        String siteUrl = properties.siteUrl() == null ? "" : properties.siteUrl().trim();
        boolean https = siteUrl.startsWith("https://");
        if (siteUrl.isBlank() || siteUrl.contains("localhost") || siteUrl.contains("127.0.0.1")) {
            String message = "blog.site-url 还是本机地址，OAuth 回跳与邮件链接都会指向 localhost。生产请设置 BLOG_SITE_URL。";
            fatal.add(message);
            warnings.add(message);
        }
        if (https && !properties.cookieSecure()) {
            String message = "站点是 https 但 blog.cookie-secure=false，浏览器会丢掉会话 cookie（表现为登录后立刻掉线）。";
            fatal.add(message);
            warnings.add(message);
        }
        if (!https && properties.cookieSecure()) {
            String message = "blog.cookie-secure=true 但站点不是 https，本地 http 下登录会失败。";
            warnings.add(message);
        }

        warnIfEmailCodeCannotWork(warnings);

        warnings.forEach(log::warn);

        if (prod && !fatal.isEmpty()) {
            throw new IllegalStateException("生产配置未就绪，拒绝启动：" + String.join(" / ", fatal));
        }
    }

    /**
     * 全新数据库里 auth.email_code_login 默认是 true，而邮件没配好时发码必然失败 ——
     * 密码对了也进不去，也就进不了后台把开关关掉。这条只能在启动时喊出来，不能替人改数据。
     */
    private void warnIfEmailCodeCannotWork(List<String> warnings) {
        boolean switchOn;
        try {
            SiteConfig row = configMapper.selectOne(Wrappers.<SiteConfig>lambdaQuery()
                    .eq(SiteConfig::getConfigKey, "auth.email_code_login"));
            switchOn = row == null || row.getConfigValue() == null
                    || !"false".equalsIgnoreCase(row.getConfigValue().trim());
        } catch (RuntimeException ex) {
            return;
        }
        // configured() 允许「没有账号的本地中继」，但二次验证要真发信到公网 SMTP，必须有账号
        boolean mailReady = properties.mail() != null && properties.mail().configured()
                && properties.mail().username() != null && !properties.mail().username().isBlank();
        if (switchOn && !mailReady) {
            warnings.add("邮箱二次验证开着，但 SMTP 没配置好（BLOG_MAIL_USERNAME / BLOG_MAIL_PASSWORD）："
                    + "密码登录会在第二步失败，谁都进不去后台。先配好邮件，或执行 "
                    + "UPDATE site_config SET config_value='false' WHERE config_key='auth.email_code_login';");
        }
    }

    private static boolean isUnset(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasWeakMarker(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return WEAK_SECRET_MARKERS.stream().anyMatch(lower::contains);
    }

}
