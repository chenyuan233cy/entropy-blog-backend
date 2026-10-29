package com.qichi.blog.modules.mail;

import com.qichi.blog.common.BizException;
import com.qichi.blog.config.BlogProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 注册验证码的发信口。用真实 SMTP（QQ / 163 / Gmail / 自建都行），配置全在
 * application.yml 的 blog.mail 下，不需要改代码。
 *
 * <p>bean 是按配置临时建的，而不是靠 spring.mail.* 自动装配 —— 因为这套配置是可选的：
 * 没填账号时应用照样启动，只是发信时报「还没配置」。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final BlogProperties.Mail config;
    private final String fallbackFrom;
    private final JavaMailSenderImpl sender;

    public MailService(BlogProperties properties) {
        this.config = properties.mail();
        this.fallbackFrom = "no-reply@" + domainOf(properties.siteUrl());
        this.sender = build();
    }

    /** 没填发件人时的兜底域名，取站点地址的主机名。 */
    private static String domainOf(String siteUrl) {
        try {
            String host = java.net.URI.create(siteUrl == null ? "" : siteUrl).getHost();
            return host == null || host.isBlank() ? "localhost" : host;
        } catch (Exception ex) {
            return "localhost";
        }
    }

    public boolean configured() {
        return config != null && config.configured();
    }

    /**
     * 配置不全就直接抛，让前端把话说清楚，别让用户对着「发送成功」等一封永远不来的信。
     *
     * <p>purpose 用来区分这封信是干什么的（register / login）：邮件标题和正文都得对得上，
     * 否则用户收到「注册验证码」而自己其实在登录，只会以为账号被人动了。
     */
    public void sendCode(String to, String code, long minutes, String purpose) {
        if (!configured()) {
            throw BizException.invalid("邮件服务还没配置，暂时收不到验证码。请先按 docs/邮箱验证码配置.md 填好发件邮箱。");
        }
        boolean login = "login".equals(purpose);
        String what = login ? "登录" : "注册";
        String subject = "【" + config.fromName() + "】" + what + "验证码";
        String html = """
                <div style="font-family:-apple-system,'Segoe UI',Roboto,sans-serif;font-size:14px;line-height:1.9;color:#222">
                  <p>你正在%s <b>%s</b>。</p>
                  <p>验证码：<b style="font-size:22px;letter-spacing:3px">%s</b></p>
                  <p style="color:#888">%d 分钟内有效。不是你本人操作就忽略这封邮件%s</p>
                </div>
                """.formatted(what, config.fromName(), code, minutes,
                login ? "，并考虑改一下密码。" : "。");
        send(to, subject, html);
    }

    public void send(String to, String subject, String html) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            // 本地中继（Mailpit 之类）没有账号，from 只能兜一个 —— 空地址会被 JavaMail 直接拒掉
            String from = config.sender();
            helper.setFrom(from == null || from.isBlank() ? fallbackFrom : from, config.fromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("mail sent to {} ({})", mask(to), subject);
        } catch (Exception ex) {
            // 把真实原因写进日志，但只给用户一句能看懂的话
            log.error("mail send failed to {}", mask(to), ex);
            // 底层 SMTP 报文里有邮件服务器地址、状态码，甚至账号线索 —— 只写日志，不回给调用方
            throw BizException.invalid("验证码发送失败，请稍后再试；一直失败请联系站长。");
        }
    }

    private JavaMailSenderImpl build() {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        if (config == null) return impl;
        impl.setHost(config.host());
        impl.setPort(config.port());
        impl.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (config.username() != null && !config.username().isBlank()) {
            impl.setUsername(config.username());
            impl.setPassword(config.password());
        }
        Properties props = impl.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(config.username() != null && !config.username().isBlank()));
        props.put("mail.smtp.ssl.enable", String.valueOf(config.ssl()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.starttls()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        return impl;
    }

    /** 日志里不写完整邮箱。 */
    private String mask(String mail) {
        if (mail == null) return "-";
        int at = mail.indexOf('@');
        if (at <= 1) return "***" + (at < 0 ? "" : mail.substring(at));
        return mail.charAt(0) + "***" + mail.substring(at);
    }

}
