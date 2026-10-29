package com.qichi.blog.modules.user.service;

import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.config.BlogProperties;
import com.qichi.blog.modules.upload.UploadService;
import com.qichi.blog.modules.user.UserDtos;
import com.qichi.blog.modules.user.entity.User;
import com.qichi.blog.modules.user.entity.UserOauth;
import com.qichi.blog.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 第三方登录与绑定。
 *
 * <p>两种进入方式共用同一条回调：
 * <ul>
 *   <li><b>登录</b>：state 里没有用户 id。按平台身份找账号 → 找不到就按邮箱认领 → 再没有就自动注册。</li>
 *   <li><b>绑定</b>：state 里带着「当前登录的人是谁」。回来就把这个平台身份挂到他账号上；
 *       如果这个身份已经属于别的账号，宁可拒绝，也不做账号合并。</li>
 * </ul>
 *
 * <p>state 存 Redis 并在回调时校验（用完即删），防止别人伪造一个 code 把用户登进错误的账号。
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
    private static final String STATE_PREFIX = "blog:oauth:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BlogProperties properties;
    private final UserService userService;
    private final AuthService authService;
    private final CacheService cache;
    private final UploadService uploadService;
    private final com.qichi.blog.modules.site.service.SiteConfigService configService;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public OAuthService(BlogProperties properties, UserService userService, AuthService authService,
                        CacheService cache, UploadService uploadService, ObjectMapper mapper,
                        com.qichi.blog.modules.site.service.SiteConfigService configService) {
        this.properties = properties;
        this.userService = userService;
        this.authService = authService;
        this.cache = cache;
        this.uploadService = uploadService;
        this.configService = configService;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ 对外

    /** 登录页要显示哪几个按钮（配置好了 **且** 站长没关），以及当前账号绑了哪几个。 */
    public List<Map<String, Object>> providers(Long viewerId) {
        List<String> bound = viewerId == null ? List.of() : userService.oauthProvidersOf(viewerId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : OAuthProviders.names()) {
            OAuthProviders.Provider provider = OAuthProviders.of(name, properties.oauth());
            boolean open = open(provider);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", provider.name());
            item.put("label", provider.label());
            item.put("enabled", open);
            // 相对 API 根，前端那个 get() 会自己补 /api/v1
            item.put("authorize", open ? "/auth/oauth/" + provider.name() + "/authorize" : "");
            item.put("bound", bound.contains(provider.name()));
            list.add(item);
        }
        return list;
    }

    /** 管理界面用：每个平台的真实状态（填没填密钥 / 开关开没开 / 几个人绑了）。 */
    public List<Map<String, Object>> adminOverview() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : OAuthProviders.names()) {
            OAuthProviders.Provider provider = OAuthProviders.of(name, properties.oauth());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", provider.name());
            item.put("label", provider.label());
            item.put("configured", provider.configured());
            item.put("switchKey", switchKey(provider.name()));
            item.put("switchedOn", switchOn(provider.name()));
            item.put("enabled", open(provider));
            item.put("boundCount", userService.countOauthBindings(provider.name()));
            list.add(item);
        }
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("name", "email-code");
        mail.put("label", "邮箱验证码（密码登录的第二道）");
        mail.put("configured", true);
        mail.put("switchKey", "auth.email_code_login");
        mail.put("switchedOn", !"false".equalsIgnoreCase(configValue("auth.email_code_login", "true")));
        list.add(mail);
        return list;
    }

    /** 配置开关 + 密钥是否齐备，两个都满足才对外可见/可用。 */
    private boolean open(OAuthProviders.Provider provider) {
        return provider.configured() && switchOn(provider.name());
    }

    private boolean switchOn(String providerName) {
        return !"false".equalsIgnoreCase(configValue("auth.oauth_" + providerName, "true"));
    }

    private String switchKey(String providerName) {
        return "auth.oauth_" + providerName;
    }

    private String configValue(String key, String fallback) {
        try {
            return configService.requireString(key, fallback);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /**
     * 发起一次授权。
     *
     * <p>要带的东西都塞在 state 里存进 Redis —— 平台跳回来时只有 code 和 state，
     * 挂在授权地址后面的 next 是回不来的（所以「登录后回到原来那一页」以前一直是失效的）。
     */
    public String authorizeUrl(String providerName, Long bindUserId, String next) {
        OAuthProviders.Provider provider = requireConfigured(providerName);
        String state = HexFormat.of().formatHex(randomBytes(16));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", provider.name());
        if (bindUserId != null) payload.put("bind", bindUserId);
        // 只接受站内路径：//evil.com 是协议相对地址，会把「登录后回到原页」变成开放重定向
        if (next != null) payload.put("next", com.qichi.blog.common.SafePath.local(next));
        cache.put(STATE_PREFIX + state, payload, STATE_TTL);
        return provider.authorizeUrl().apply(state);
    }

    /** 回调结果：登录成功给令牌（外加原来想去的页面）；绑定成功只给一句话。 */
    public record Callback(UserDtos.LoginResult session, String boundProvider, boolean bound, String next) {}

    @SuppressWarnings("unchecked")
    @Transactional
    public Callback handleCallback(String providerName, String code, String state) {
        OAuthProviders.Provider provider = requireConfigured(providerName);
        if (code == null || code.isBlank()) throw BizException.invalid(provider.label() + " 没有返回授权码");
        if (state == null || state.isBlank()) throw BizException.invalid("缺少 state，拒绝这次请求");

        Object saved = cache.get(STATE_PREFIX + state, Map.class);
        cache.delete(STATE_PREFIX + state);
        if (!(saved instanceof Map)) throw BizException.invalid("这次操作已过期或不是从本站发起的，请重新来一次");

        Map<String, Object> payload = (Map<String, Object>) saved;
        if (!provider.name().equals(String.valueOf(payload.get("provider")))) {
            throw BizException.invalid("state 与登录方式对不上");
        }
        String next = payload.get("next") == null ? null : String.valueOf(payload.get("next"));

        String accessToken = exchange(provider, code);
        OAuthProviders.Profile profile = readProfile(provider, accessToken);
        if (profile.openId() == null || profile.openId().isBlank() || "0".equals(profile.openId())) {
            throw BizException.invalid("读不到 " + provider.label() + " 的账号标识");
        }

        // 绑定：谁发起的就挂到谁账号上，不碰登录态
        if (payload.get("bind") != null) {
            long bindUserId = Long.parseLong(String.valueOf(payload.get("bind")));
            bind(bindUserId, provider, profile);
            return new Callback(null, provider.name(), true, null);
        }

        return new Callback(loginOrRegister(provider, profile), null, false, next);
    }

    /** 解绑。会拦住「把自己最后一条登录路径也解掉」这种情况。 */
    @Transactional
    public void unbind(Long userId, String providerName) {
        OAuthProviders.Provider provider = OAuthProviders.of(providerName, properties.oauth());
        User user = userService.requireById(userId);
        List<String> bound = userService.oauthProvidersOf(userId);
        if (!bound.contains(provider.name())) throw BizException.invalid("没有绑定 " + provider.label());
        if (!userService.hasPassword(user) && bound.size() <= 1) {
            throw new BizException(com.qichi.blog.common.ErrorCode.FORBIDDEN,
                    "这是你唯一的登录方式。先设一个密码，再解绑。");
        }
        userService.unbindOauth(userId, provider.name());
        log.info("user {} unbound {}", user.getUsername(), provider.name());
    }

    // ------------------------------------------------------------------ 认人

    private UserDtos.LoginResult loginOrRegister(OAuthProviders.Provider provider, OAuthProviders.Profile profile) {
        User existing = userService.findByOauth(provider.name(), profile.openId());
        if (existing != null) {
            userService.touchLastLogin(existing.getId());
            return authService.issue(principalOf(existing));
        }

        // 邮箱对得上就认成同一个人（平台已验证过的邮箱），否则这人会有两个账号
        if (profile.emailVerified() && !profile.email().isBlank()) {
            User byMail = userService.findByEmail(profile.email());
            if (byMail != null) {
                userService.bindOauth(byMail.getId(), provider.name(), profile.openId(), profile.email(),
                        profile.nickname(), profile.avatar());
                userService.touchLastLogin(byMail.getId());
                log.info("{} {} linked to existing account {}", provider.name(), profile.openId(), byMail.getUsername());
                return authService.issue(principalOf(byMail));
            }
        }

        User created = userService.createOauthAccount(provider.name(), profile.openId(), profile.email(), null,
                profile.nickname());
        String stored = localizeAvatar(profile.avatar(), created.getId());
        // 头像落到我们自己的存储里；失败就保持没有头像（宁可显示首字母，也不存一个注定加载不出的外链）
        if (stored != null) {
            userService.bindOauth(created.getId(), provider.name(), profile.openId(), profile.email(),
                    profile.nickname(), stored);
        }
        log.info("{} created account {}", provider.name(), created.getUsername());
        return authService.issue(principalOf(created));
    }

    private void bind(long userId, OAuthProviders.Provider provider, OAuthProviders.Profile profile) {
        User owner = userService.findByOauth(provider.name(), profile.openId());
        if (owner != null && !owner.getId().equals(userId)) {
            // 不做账号合并：合并要人工核对，做错了就是把别人的数据并进来
            throw new BizException(com.qichi.blog.common.ErrorCode.CONFLICT,
                    "这个 " + provider.label() + " 账号已经绑在另一个账号（" + owner.getUsername()
                            + "）上了。请先用那个账号登录并解绑。");
        }
        if (owner != null) {
            throw BizException.invalid("这个 " + provider.label() + " 账号已经绑在当前账号上了");
        }
        userService.bindOauth(userId, provider.name(), profile.openId(), profile.email(),
                profile.nickname(), profile.avatar());
        log.info("user {} bound {}", userId, provider.name());
    }

    // ------------------------------------------------------------------ HTTP

    private OAuthProviders.Provider requireConfigured(String providerName) {
        OAuthProviders.Provider provider = OAuthProviders.of(providerName, properties.oauth());
        if (!provider.configured()) {
            throw new BizException(com.qichi.blog.common.ErrorCode.UNAVAILABLE,
                    provider.label() + " 登录还没配置，请填写 blog.oauth." + provider.name() + "-client-id 与 client-secret");
        }
        // 站长关掉的入口，硬敲地址也进不去
        if (!switchOn(provider.name())) {
            throw new BizException(com.qichi.blog.common.ErrorCode.FORBIDDEN,
                    provider.label() + " 登录已被站长关闭");
        }
        return provider;
    }

    private String exchange(OAuthProviders.Provider provider, String code) {
        StringBuilder body = new StringBuilder();
        OAuthProviders.tokenForm(provider, code).forEach((key, value) ->
                body.append(body.isEmpty() ? "" : "&").append(OAuthProviders.encode(key)).append("=")
                        .append(OAuthProviders.encode(value)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(provider.tokenUri()))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        JsonNode node = send(request);
        String token = node.path("access_token").asString("");
        if (token.isBlank()) {
            String reason = node.path("error_description").asString(node.path("error").asString("未知原因"));
            throw BizException.invalid(provider.label() + " 拒绝了这次授权：" + reason);
        }
        return token;
    }

    private OAuthProviders.Profile readProfile(OAuthProviders.Provider provider, String accessToken) {
        JsonNode node = get(provider.profileUri(), accessToken, provider.label())
                .orElseThrow(() -> BizException.invalid("读不到 " + provider.label() + " 的资料"));
        OAuthProviders.Profile profile = provider.parseProfile().apply(node);

        // GitHub 的公开资料里邮箱常常是空的，单独问一次；Google 在 userinfo 里就给了
        if (profile.email().isBlank() && provider.emailsUri() != null) {
            String mail = get(provider.emailsUri(), accessToken, provider.label())
                    .map(provider.parsePrimaryEmail())
                    .orElse(null);
            if (mail != null && !mail.isBlank()) {
                profile = new OAuthProviders.Profile(profile.openId(), mail, true, profile.nickname(), profile.avatar());
            }
        }
        return profile;
    }

    private Optional<JsonNode> get(String url, String accessToken, String label) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        try {
            return Optional.of(send(request));
        } catch (RuntimeException ex) {
            log.warn("{} api {} failed: {}", label, url, ex.getMessage());
            return Optional.empty();
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw BizException.invalid("对方接口返回 " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (BizException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw BizException.invalid("连接被中断");
        } catch (javax.net.ssl.SSLException ex) {
            // 证书/握手失败十有八九是网络到不了对方（hosts 屏蔽、代理），PKIX 那串英文没人看得懂
            throw BizException.invalid("连不上对方（TLS 握手失败）。检查网络，或看看 hosts／代理是不是把它指向了本机。");
        } catch (java.net.ConnectException | java.net.UnknownHostException ex) {
            throw BizException.invalid("连不上对方，请检查网络。");
        } catch (Exception ex) {
            throw BizException.invalid("请求失败：" + ex.getMessage());
        }
    }

    /** 把第三方头像下载到我们自己的 uploads 里，返回站内地址。 */
    private String localizeAvatar(String remote, long userId) {
        if (remote == null || remote.isBlank()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(remote))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) return null;
            return uploadService.storeBytes(response.body(),
                    "users/" + userService.resourceKey(userId) + "/resources/avatar");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            log.warn("could not download avatar for {}: {}", userId, ex.getMessage());
            return null;
        }
    }

    private LoginUser principalOf(User user) {
        return new LoginUser(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
