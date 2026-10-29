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

/**
 * 启动自检：把「配置忘了改」从线上事故变成一条启动日志（生产 profile 下直接拒绝启动）。
 *
 * <p>这里只查那些「不改也没报错，但一上线就出人命」的项，别的配置项错了自然会表现为功能异常。
 */
@Component
@Order(100)
public class StartupSecurityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityCheck.class);

    /** 仓库里公开的开发默认值：知道它就能伪造任何人的令牌。 */
    private static final String DEV_JWT_SECRET = "<REDACTED>";
    private static final String DEV_DB_PASSWORD = "<REDACTED>";

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

        if (DEV_JWT_SECRET.equals(properties.jwt().secret())) {
            String message = "blog.jwt.secret 还是仓库里的开发默认值，任何人都能伪造站长令牌。"
                    + " 生产必须设置 BLOG_JWT_SECRET（openssl rand -base64 48）。";
            fatal.add(message);
            warnings.add(message);
        }

        if (DEV_DB_PASSWORD.equals(environment.getProperty("spring.datasource.password"))) {
            String message = "数据库密码还是开发默认值，请设置 SPRING_DATASOURCE_PASSWORD。";
            fatal.add(message);
            warnings.add(message);
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
}
