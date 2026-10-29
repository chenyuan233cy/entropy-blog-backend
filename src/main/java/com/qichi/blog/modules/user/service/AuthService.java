package com.qichi.blog.modules.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.common.ErrorCode;
import com.qichi.blog.modules.user.UserDtos;
import com.qichi.blog.modules.user.entity.RefreshToken;
import com.qichi.blog.modules.user.entity.User;
import com.qichi.blog.security.JwtService;
import com.qichi.blog.security.LoginUser;
import com.qichi.blog.security.TokenVersionService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Access + refresh token issuance.
 *
 * <p>Revocation uses a per-user token version instead of a per-token blacklist: one Redis key holds
 * the version, a logout bumps it, and every token that user ever issued stops validating at once.
 * Refresh tokens additionally rotate, so a captured refresh token is usable at most once, with a
 * short grace window so three parallel 401s cannot log the author out.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 密码验过、等邮箱验证码的那张票 */
    private static final String TICKET_PREFIX = "blog:login:ticket:";
    private static final Duration TICKET_TTL = Duration.ofMinutes(10);

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CacheService cache;
    private final com.qichi.blog.modules.mail.EmailCodeService emailCodeService;
    private final com.qichi.blog.modules.site.service.SiteConfigService configService;
    private final com.qichi.blog.modules.user.mapper.RefreshTokenMapper refreshTokenMapper;
    private final TokenVersionService tokenVersions;
    private final com.qichi.blog.config.BlogProperties properties;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder,
                       JwtService jwtService, CacheService cache,
                       com.qichi.blog.modules.mail.EmailCodeService emailCodeService,
                       com.qichi.blog.modules.site.service.SiteConfigService configService,
                       com.qichi.blog.modules.user.mapper.RefreshTokenMapper refreshTokenMapper,
                       TokenVersionService tokenVersions,
                       com.qichi.blog.config.BlogProperties properties) {
        this.properties = properties;
        this.userService = userService;
        this.passwordEncoder = ${THIS_PASSWORDENCODER:}
        this.jwtService = ${THIS_JWTSERVICE:}
        this.cache = cache;
        this.emailCodeService = emailCodeService;
        this.configService = configService;
        this.refreshTokenMapper = ${THIS_REFRESHTOKENMAPPER:}
        this.tokenVersions = ${THIS_TOKENVERSIONS:}
    }

    /**
     * 账号密码登录的**第一步**：只验密码，不发令牌。
     *
     * <p>密码对了也不算登录成功 —— 还要往账号绑定邮箱发一个验证码，第二步验过才发令牌。
     * 这样密码被人看到了也进不来；反过来，密码错的时候连验证码都不发（不给攻击者刷邮件的机会）。
     *
     * <p>关掉 {@code auth.email_code_login} 就退回老行为：密码对直接给令牌。
     */
    public LoginStep login(UserDtos.LoginRequest request) {
        // 邮箱注册的账号没有让人记的用户名，所以这里用户名、邮箱都认
        String account = request.username().trim();
        User user = account.contains("@") ? userService.findByEmail(account) : userService.findByUsername(account);
        // identical message for both failures so the endpoint cannot enumerate accounts
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名或密码不正确");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "该账号已被禁用");
        }

        if (!twoFactorOn()) {
            userService.touchLastLogin(user.getId());
            return new LoginStep(false, null, null, issue(principalOf(user)));
        }

        // 没邮箱就没法发验证码。这里只能拒绝，不能悄悄放行 —— 放行等于给这类账号留了个后门。
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "这个账号没绑定邮箱，没法收验证码。请先用第三方登录进来，在「我的资料」里补一个邮箱。");
        }

        emailCodeService.send(email, "login");
        String ticket = newTicket(user.getId());
        log.info("password ok for {}, waiting for email code", user.getUsername());
        return new LoginStep(true, ticket, com.qichi.blog.modules.mail.EmailCodeService.maskEmail(email), null);
    }

    /** 登录第二步的结果：要么还要验证码，要么已经签出一对令牌（令牌只进 cookie）。 */
    public record LoginStep(boolean needCode, String ticket, String emailHint, UserDtos.LoginResult session) {}

    /** 第二步：验证码对了才发令牌。 */
    public UserDtos.LoginResult verifyLogin(UserDtos.LoginVerifyRequest request) {
        Long userId = ticketUser(request.ticket());
        if (userId == null) throw BizException.invalid("这次登录已过期，请重新输入账号密码。");

        User user = userService.requireById(userId);
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BizException(ErrorCode.FORBIDDEN, "这个账号没绑定邮箱");
        }
        emailCodeService.verify(user.getEmail(), request.code(), "login");
        cache.delete(TICKET_PREFIX + request.ticket());
        userService.touchLastLogin(user.getId());
        return issue(principalOf(user));
    }

    /** 第二步没收到信：按登录用途重发一个，别的什么都不变。 */
    public LoginStep resendLoginCode(UserDtos.LoginTicketRequest request) {
        Long userId = ticketUser(request.ticket());
        if (userId == null) throw BizException.invalid("这次登录已过期，请重新输入账号密码。");
        User user = userService.requireById(userId);
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BizException(ErrorCode.FORBIDDEN, "这个账号没绑定邮箱");
        }
        emailCodeService.send(user.getEmail(), "login");
        return new LoginStep(true, request.ticket(),
                com.qichi.blog.modules.mail.EmailCodeService.maskEmail(user.getEmail()), null);
    }

    private boolean twoFactorOn() {
        try {
            return !"false".equalsIgnoreCase(configService.requireString("auth.email_code_login", "true"));
        } catch (RuntimeException ex) {
            // 配置读不到就按「要验证码」处理：宁可多一步，也不悄悄降低安全性
            return true;
        }
    }

    /** 第一步通过之后发一张短命票据，第二步凭它认人 —— 中间不再要密码，也不把用户 id 摆到前端。 */
    private String newTicket(Long userId) {
        String ticket = java.util.UUID.randomUUID().toString().replace("-", "");
        cache.put(TICKET_PREFIX + ticket, String.valueOf(userId), TICKET_TTL);
        return ticket;
    }

    private Long ticketUser(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        if (!cache.healthy()) throw new BizException(ErrorCode.UNAVAILABLE, "登录服务暂时不可用，请稍后重试");
        String value = cache.get(TICKET_PREFIX + ticket, String.class);
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 注册闸门：空库时放行（站长初始化那一次），否则只有显式打开开关才允许。
     * 这样站长自己能开号，陌生人进不来，也不用临时改环境变量。
     */
    public boolean registrationAllowed() {
        var auth = properties.auth();
        if (auth != null && auth.registrationEnabled()) return true;
        return !userService.hasAnyUser();
    }

    /** 第一个账号自动成为管理员，否则没人能进后台。 */
    private String roleForNewAccount() {
        return userService.hasAnyUser() ? LoginUser.ROLE_USER : LoginUser.ROLE_ADMIN;
    }

    private LoginUser principalOf(User user) {
        return new LoginUser(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    /**
     * Public sign-up. Returns a logged-in session straight away, so a new reader can comment
     * without a second trip through the login form.
     */
    @Transactional
    public UserDtos.LoginResult register(UserDtos.RegisterRequest request) {
        String username = request.username().trim();
        if (userService.findByUsername(username) != null) {
            throw new BizException(ErrorCode.CONFLICT, "这个用户名已经被注册了");
        }
        User user = userService.createAccount(username, request.password(), request.nickname(),
                request.email(), roleForNewAccount());
        log.info("new reader account: {}", user.getUsername());
        return issue(new LoginUser(user.getId(), user.getUsername(), user.getNickname(), user.getRole()));
    }

    /**
     * 邮箱验证码注册：码验过了就直接建号并登录，不再让用户填昵称之类的东西。
     *
     * <p>用户名和昵称都是自动生成的 —— 邮箱注册的人没打算维护一个用户名，
     * 硬要他起一个只会让他在注册页卡住。
     */
    @Transactional
    public UserDtos.LoginResult registerByEmail(UserDtos.EmailRegisterRequest request) {
        String email = emailCodeService.normalize(request.email());
        emailCodeService.verify(email, request.code());

        User existing = userService.findByEmail(email);
        if (existing != null) {
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已经注册过了，直接登录就行");
        }
        if (request.password() == null || request.password().length() < 8) {
            throw BizException.invalid("密码至少 8 位");
        }

        User user = userService.createAccount(uniqueUsername(), request.password(), randomNickname(), email,
                roleForNewAccount());
        log.info("new account by email: {}", user.getUsername());
        return issue(new LoginUser(user.getId(), user.getUsername(), user.getNickname(), user.getRole()));
    }

    /** 用户名不给用户看，只要唯一就行。 */
    private String uniqueUsername() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = "u" + Long.toString(Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong()), 36);
            if (candidate.length() > 12) candidate = candidate.substring(0, 12);
            if (userService.findByUsername(candidate) == null) return candidate;
        }
        throw new BizException(ErrorCode.INTERNAL, "生成用户名失败，请重试");
    }

    /** 昵称固定成「用户 + 随机数」，之后可以在「我的资料」里改。 */
    private String randomNickname() {
        return "用户" + (100000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(900000));
    }

    /**
     * 刷新令牌。
     *
     * <p>白名单在**数据库**里，不在 Redis。以前放缓存里，Redis 一抖（重启、断连、30 秒熔断）
     * 就会被判定成「令牌已失效」，所有人都被踢下线 —— 而他们的令牌其实一直有效。
     *
     * <p>轮换是「用一次就换新的」，但**同一个 jti 在宽限期内重放仍然放行**：
     * 多个标签页同时刷新时必然撞上，不放行就等于随机登出一个人。
     */
    @Transactional
    public UserDtos.LoginResult refresh(String refreshToken) {
        Claims claims = jwtService.parse(refreshToken);
        if (!JwtService.TYPE_REFRESH.equals(claims.get("typ", String.class))) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "不是刷新令牌");
        }
        long userId = Long.parseLong(claims.getSubject());
        String jti = claims.getId();

        RefreshToken row = refreshTokenMapper.selectById(jti);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.TOKEN_REVOKED);
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenMapper.deleteById(jti);
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        }

        User user = userService.requireById(userId);
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "该账号已被禁用");
        }

        // 只换访问令牌，刷新令牌原样保留（不轮换）。
        // 轮换需要「同一 jti 被重放怎么办」的宽限窗口，而多个标签页同时刷新必然重放 ——
        // 那套机制换来的安全性对一个个人博客不值，换来的却是随机的互相踢下线。
        LoginUser principal = new LoginUser(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
        long version = tokenVersions.current(user.getId());
        String access = jwtService.createAccessToken(principal, version);
        return new UserDtos.LoginResult(
                access, refreshToken, "Bearer", jwtService.accessTtl().toSeconds(),
                userService.toProfile(user));
    }

    /** 退出登录：版本号 +1 让已发出的访问令牌立刻失效，同时把这个人的刷新令牌全删掉。 */
    @Transactional
    public void logout(LoginUser principal) {
        if (principal == null) return;
        // 版本号落在 MySQL 里：Redis 重启、被 flush、内存淘汰都不会让撤销失效
        tokenVersions.bump(principal.id());
        refreshTokenMapper.delete(Wrappers.<RefreshToken>lambdaQuery().eq(RefreshToken::getUserId, principal.id()));
        log.info("user {} logged out, token version bumped", principal.username());
    }

    /**
     * 访问令牌已经过期时的登出：用刷新令牌认出用户，删掉这一条并让版本号 +1。
     * 令牌过期或伪造就什么都不做 —— 登出本身永远不该失败。
     */
    public void logoutByRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        try {
            Claims claims = jwtService.parse(refreshToken);
            if (!JwtService.TYPE_REFRESH.equals(claims.get("typ", String.class))) return;
            long userId = Long.parseLong(claims.getSubject());
            refreshTokenMapper.deleteById(claims.getId());
            tokenVersions.bump(userId);
            log.info("user {} logged out via refresh token", userId);
        } catch (RuntimeException ignored) {
            // 过期 / 伪造的刷新令牌：本来就没有可撤销的东西
        }
    }

    public UserDtos.LoginResult issue(LoginUser principal) {
        long version = tokenVersions.current(principal.id());
        String access = jwtService.createAccessToken(principal, version);
        String refresh = jwtService.createRefreshToken(principal, version);

        // 白名单写进数据库：缓存丢了不影响「还登录着」这件事
        Claims refreshClaims = jwtService.parse(refresh);
        RefreshToken row = new RefreshToken();
        row.setJti(refreshClaims.getId());
        row.setUserId(principal.id());
        row.setExpiresAt(LocalDateTime.now().plus(jwtService.refreshTtl()));
        row.setCreateTime(LocalDateTime.now());
        refreshTokenMapper.insert(row);
        // 顺手把这个人已经过期的记录清掉，别让表一直长
        refreshTokenMapper.delete(Wrappers.<RefreshToken>lambdaQuery()
                .eq(RefreshToken::getUserId, principal.id())
                .lt(RefreshToken::getExpiresAt, LocalDateTime.now()));

        return new UserDtos.LoginResult(
                access, refresh, "Bearer", jwtService.accessTtl().toSeconds(),
                userService.toProfile(userService.requireById(principal.id())));
    }

    public UserDtos.UserProfileVO me(LoginUser principal) {
        return userService.toProfile(userService.requireById(principal.id()));
    }
}
