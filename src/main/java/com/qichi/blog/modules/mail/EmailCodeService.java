package com.qichi.blog.modules.mail;

import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.config.BlogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 邮箱验证码：生成、发信、校验。
 *
 * <p>验证码存在 Redis 里带过期时间，验过就删；同一邮箱 60 秒内只能要一次，一个验证码最多试 5 次 ——
 * 不然 6 位数字靠暴力试也能撞开。
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);
    private static final String CODE_PREFIX = "blog:email:code:";
    private static final String SEND_PREFIX = "blog:email:sent:";
    private static final String TRY_PREFIX = "blog:email:try:";
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final int MAX_TRIES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CacheService cache;
    private final MailService mailService;
    private final BlogProperties.Mail config;

    public EmailCodeService(CacheService cache, MailService mailService, BlogProperties properties) {
        this.cache = cache;
        this.mailService = mailService;
        this.config = properties.mail();
    }

    public boolean mailReady() {
        return mailService.configured();
    }

    /** 为了不在接口里泄露「这个邮箱注册没注册过」，发码阶段不做账号存在性判断。 */
    public void send(String rawEmail) {
        send(rawEmail, "register");
    }

    /**
     * 发一个验证码。
     *
     * <p>purpose 会把「注册的码」和「登录的码」隔开：不然拿注册流程骗出来的码，
     * 可以直接拿去当登录的第二因子用。
     */
    public void send(String rawEmail, String purpose) {
        String email = normalize(rawEmail);
        String scope = scope(purpose);
        Duration interval = config != null && config.resendInterval() != null
                ? config.resendInterval() : Duration.ofSeconds(60);
        if (!cache.setIfAbsent(SEND_PREFIX + scope + email, "1", interval)) {
            throw BizException.invalid("验证码刚发过，请稍等一分钟再试。");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Duration ttl = config != null && config.codeTtl() != null ? config.codeTtl() : Duration.ofMinutes(5);
        cache.put(CODE_PREFIX + scope + email, code, ttl);
        cache.delete(TRY_PREFIX + scope + email);
        try {
            mailService.sendCode(email, code, Math.max(1, ttl.toMinutes()), scope);
        } catch (RuntimeException ex) {
            // 发失败就把冷却和验证码一起撤掉，用户可以立刻重试
            cache.delete(SEND_PREFIX + scope + email);
            cache.delete(CODE_PREFIX + scope + email);
            throw ex;
        }
        log.info("email code issued for {} ({})", mask(email), scope);
    }

    /** 校验通过就把验证码作废，同一个码不能用两次。 */
    public void verify(String rawEmail, String code) {
        verify(rawEmail, code, "register");
    }

    public void verify(String rawEmail, String code, String purpose) {
        String email = normalize(rawEmail);
        String scope = scope(purpose);
        if (code == null || code.isBlank()) throw BizException.invalid("请填写验证码");
        String saved = cache.get(CODE_PREFIX + scope + email, String.class);
        if (saved == null) throw BizException.invalid("验证码已过期，请重新发送。");

        cache.setIfAbsent(TRY_PREFIX + scope + email, "0", Duration.ofMinutes(10));
        long tries = cache.increment(TRY_PREFIX + scope + email, 1L);
        if (tries > MAX_TRIES) {
            cache.delete(CODE_PREFIX + scope + email);
            throw BizException.invalid("试错次数太多，请重新获取验证码。");
        }
        if (!saved.equals(code.trim())) throw BizException.invalid("验证码不正确");

        cache.delete(CODE_PREFIX + scope + email);
        cache.delete(TRY_PREFIX + scope + email);
    }

    /** 只允许这两种用途，别处传进来的值不能变成新的命名空间。 */
    private String scope(String purpose) {
        return "login".equals(purpose) ? "login:" : "";
    }

    public String normalize(String email) {
        if (email == null || email.isBlank()) throw BizException.invalid("请填写邮箱");
        String value = email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 120 || !EMAIL.matcher(value).matches()) {
            throw BizException.invalid("邮箱格式看起来不对");
        }
        return value;
    }

    /** 1***@qq.com：日志和前端都只显示这个，完整地址不外露。 */
    public static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.charAt(0) + "***" + email.substring(at);
    }

    private String mask(String email) {
        return maskEmail(email);
    }
}
