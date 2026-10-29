package com.qichi.blog.modules.user.controller;

import com.qichi.blog.common.BizException;
import com.qichi.blog.common.ErrorCode;
import com.qichi.blog.common.R;
import com.qichi.blog.common.SafePath;
import com.qichi.blog.config.BlogProperties;
import com.qichi.blog.modules.mail.EmailCodeService;
import com.qichi.blog.modules.user.UserDtos;
import com.qichi.blog.modules.user.service.AuthService;
import com.qichi.blog.modules.user.service.OAuthService;
import com.qichi.blog.security.AuthCookies;
import com.qichi.blog.security.LoginUser;
import com.qichi.blog.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证入口。
 *
 * <p>令牌**只**通过 HttpOnly cookie 下发（见 {@link AuthCookies}），响应体里永远不出现
 * accessToken / refreshToken —— 前端脚本读不到，XSS 也偷不走。刷新令牌也只发给
 * /api/v1/auth 这几个接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证")
public class AuthController {

    private final AuthService authService;
    private final BlogProperties properties;
    private final EmailCodeService emailCodeService;
    private final OAuthService oAuthService;

    public AuthController(AuthService authService, BlogProperties properties,
                          EmailCodeService emailCodeService, OAuthService oAuthService) {
        this.authService = authService;
        this.properties = properties;
        this.emailCodeService = emailCodeService;
        this.oAuthService = oAuthService;
    }

    /**
     * 单作者站点默认不开放自助注册。三个注册入口都先过这里：
     * 关着的时候返回 403 与明确文案，而不是让人填完表单才发现建不了号。
     */
    private void requireRegistrationOpen() {
        if (!authService.registrationAllowed()) {
            throw new BizException(ErrorCode.FORBIDDEN, "本站未开放注册");
        }
    }

    @PostMapping("/login")
    @Operation(summary = "账号密码登录的第一步：验密码，然后发邮箱验证码（配置里可以关掉二次验证）")
    public R<UserDtos.LoginStepVO> login(@Valid @RequestBody UserDtos.LoginRequest request,
                                         HttpServletResponse response) {
        AuthService.LoginStep step = authService.login(request);
        if (step.session() != null) issueCookies(response, step.session());
        return R.ok(new UserDtos.LoginStepVO(step.needCode(), step.ticket(), step.emailHint(),
                UserDtos.SessionVO.of(step.session())));
    }

    @PostMapping("/login/verify")
    @Operation(summary = "账号密码登录的第二步：验证码换会话")
    public R<UserDtos.SessionVO> loginVerify(@Valid @RequestBody UserDtos.LoginVerifyRequest request,
                                             HttpServletResponse response) {
        UserDtos.LoginResult session = authService.verifyLogin(request);
        issueCookies(response, session);
        return R.ok(UserDtos.SessionVO.of(session));
    }

    @PostMapping("/login/code")
    @Operation(summary = "第二步没收到信，重发登录验证码")
    public R<UserDtos.LoginStepVO> loginCode(@Valid @RequestBody UserDtos.LoginTicketRequest request) {
        AuthService.LoginStep step = authService.resendLoginCode(request);
        return R.ok(new UserDtos.LoginStepVO(step.needCode(), step.ticket(), step.emailHint(), null));
    }

    @PostMapping("/register")
    @Operation(summary = "公开注册普通读者账号（老的用户名注册，保留兼容）")
    public R<UserDtos.SessionVO> register(@Valid @RequestBody UserDtos.RegisterRequest request,
                                          HttpServletResponse response) {
        requireRegistrationOpen();
        UserDtos.LoginResult session = authService.register(request);
        issueCookies(response, session);
        return R.ok(UserDtos.SessionVO.of(session));
    }

    @PostMapping("/email/code")
    @Operation(summary = "给邮箱发一个注册验证码")
    public R<Map<String, Object>> emailCode(@Valid @RequestBody UserDtos.EmailCodeRequest request) {
        requireRegistrationOpen();
        emailCodeService.send(request.email());
        return R.ok(Map.of("sent", true, "mailReady", emailCodeService.mailReady()));
    }

    @PostMapping("/email/register")
    @Operation(summary = "用邮箱验证码注册，成功后直接登录")
    public R<UserDtos.SessionVO> emailRegister(@Valid @RequestBody UserDtos.EmailRegisterRequest request,
                                               HttpServletResponse response) {
        requireRegistrationOpen();
        UserDtos.LoginResult session = authService.registerByEmail(request);
        issueCookies(response, session);
        return R.ok(UserDtos.SessionVO.of(session));
    }

    @PostMapping("/refresh")
    @Operation(summary = "用 cookie 里的刷新令牌换一个新的访问令牌")
    public R<UserDtos.SessionVO> refresh(HttpServletRequest request, HttpServletResponse response) {
        String token = AuthCookies.read(request, AuthCookies.REFRESH);
        if (token == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "会话已过期，请重新登录");
        }
        UserDtos.LoginResult session = authService.refresh(token);
        issueCookies(response, session);
        return R.ok(UserDtos.SessionVO.of(session));
    }

    /**
     * 登出不要求登录态：访问令牌过期时也必须能走到这里，否则刷新令牌会留在浏览器里，
     * 下次打开页面又被静默登回去。没有有效凭证时它只是清 cookie。
     */
    @PostMapping("/logout")
    @Operation(summary = "登出，提升令牌版本使该用户签发的所有令牌立即失效")
    public R<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityUtils.currentUser().ifPresent(authService::logout);
        authService.logoutByRefreshToken(AuthCookies.read(request, AuthCookies.REFRESH));
        AuthCookies.clear(response, properties.cookieSecure());
        return R.ok();
    }

    @GetMapping("/me")
    @Operation(summary = "当前登录用户")
    public R<UserDtos.UserProfileVO> me() {
        return R.ok(authService.me(SecurityUtils.requireUser()));
    }

    @GetMapping("/oauth/providers")
    @Operation(summary = "哪些第三方登录可用；登录了还会带上「绑没绑过」，我的资料按它显示绑定状态")
    public R<java.util.List<Map<String, Object>>> providers() {
        Long viewerId = SecurityUtils.currentUser().map(LoginUser::id).orElse(null);
        return R.ok(oAuthService.providers(viewerId));
    }

    /**
     * 授权地址。带 bind=1 就是「给我当前这个账号绑一个新平台」，
     * 不带就是普通登录 —— 两条路共用同一个回调。
     */
    @GetMapping("/oauth/{provider}/authorize")
    @Operation(summary = "拿某个平台的授权地址")
    public R<Map<String, String>> authorize(@PathVariable String provider,
                                            @RequestParam(required = false) String bind,
                                            @RequestParam(required = false) String next) {
        Long bindUserId = "1".equals(bind) ? SecurityUtils.requireUser().id() : null;
        return R.ok(Map.of("authorizeUrl", oAuthService.authorizeUrl(provider, bindUserId, SafePath.local(next))));
    }

    /**
     * 第三方回调。它是一次浏览器跳转（不是 fetch），所以这里直接 302 回前端：
     * 令牌写进 HttpOnly cookie 随 302 一起下发，地址栏里只有 ok/error，什么都没有。
     */
    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(required = false) String next,
                                         HttpServletResponse response) {
        String base = properties.siteUrl() == null || properties.siteUrl().isBlank()
                ? "http://localhost:3000" : properties.siteUrl().replaceAll("/+$", "");
        if (error != null && !error.isBlank()) {
            return redirect(base + "/oauth/callback?error=" + encode(error));
        }
        try {
            OAuthService.Callback result = oAuthService.handleCallback(provider, code, state);
            if (result.bound()) {
                // 绑定是登录状态下发起的，回到「我的资料」并告诉她绑好了
                return redirect(base + "/me?bound=" + encode(result.boundProvider()));
            }
            issueCookies(response, result.session());
            String target = SafePath.local(result.next() != null ? result.next() : next);
            String location = base + "/oauth/callback?ok=1" + (target == null ? "" : "&next=" + encode(target));
            return redirect(location);
        } catch (BizException ex) {
            return redirect(base + "/oauth/callback?error=" + encode(ex.getMessage()));
        }
    }

    @DeleteMapping("/oauth/{provider}")
    @Operation(summary = "解绑一个第三方账号（唯一登录方式时会被拒绝）")
    public R<Void> unbind(@PathVariable String provider) {
        oAuthService.unbind(SecurityUtils.requireUser().id(), provider);
        return R.ok();
    }

    private void issueCookies(HttpServletResponse response, UserDtos.LoginResult session) {
        AuthCookies.write(response, properties, session.accessToken(), session.refreshToken());
    }

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(302).location(java.net.URI.create(url)).build();
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
