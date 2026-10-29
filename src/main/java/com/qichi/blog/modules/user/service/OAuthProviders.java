package com.qichi.blog.modules.user.service;

import com.qichi.blog.common.BizException;
import com.qichi.blog.config.BlogProperties;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 各平台的差异都收在这里：授权地址、换 token 的地址、读资料的地址、以及怎么从资料里
 * 认出「这个人是谁」。
 *
 * <p>加一个新平台 = 加一个 Provider，不用碰登录/绑定流程 —— state 校验、自动注册、
 * 头像落地、解绑保护都在 {@link OAuthService} 里。
 */
public final class OAuthProviders {

    private OAuthProviders() {}

    /** openId 必须是平台侧永不改变的那个 id：GitHub 用数字 id（改名也不变），Google 用 sub。 */
    public record Profile(String openId, String email, boolean emailVerified, String nickname, String avatar) {}

    public record Provider(
            String name,
            String label,
            String clientId,
            String clientSecret,
            String redirectUri,
            /** 已经把 state 拼进去的授权地址 */
            Function<String, String> authorizeUrl,
            String tokenUri,
            String profileUri,
            /** GitHub 的公开资料常常没有邮箱，得单独再问一次；Google 一次就给全 */
            String emailsUri,
            Function<JsonNode, Profile> parseProfile,
            /** 从 /user/emails 那种列表里挑出「主邮箱且已验证」的那个 */
            Function<JsonNode, String> parsePrimaryEmail) {

        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public static List<String> names() {
        return List.of("github", "google");
    }

    public static Provider of(String name, BlogProperties.Oauth oauth) {
        return switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
            case "github" -> github(oauth);
            case "google" -> google(oauth);
            default -> throw BizException.notFound("登录方式");
        };
    }

    private static Provider github(BlogProperties.Oauth oauth) {
        return new Provider("github", "GitHub",
                oauth.githubClientId(), oauth.githubClientSecret(), oauth.githubRedirectUri(),
                state -> "https://github.com/login/oauth/authorize"
                        + "?client_id=" + encode(oauth.githubClientId())
                        + "&redirect_uri=" + encode(oauth.githubRedirectUri())
                        + "&scope=" + encode("read:user user:email")
                        + "&state=" + state,
                "https://github.com/login/oauth/access_token",
                "https://api.github.com/user",
                "https://api.github.com/user/emails",
                node -> new Profile(
                        String.valueOf(node.path("id").asLong()),
                        text(node, "email"),
                        false,
                        firstNonBlank(text(node, "name"), text(node, "login")),
                        text(node, "avatar_url")),
                node -> {
                    if (!node.isArray()) return null;
                    for (JsonNode item : node) {
                        if (item.path("primary").asBoolean(false) && item.path("verified").asBoolean(false)) {
                            String mail = text(item, "email");
                            if (!mail.isBlank()) return mail.toLowerCase(Locale.ROOT);
                        }
                    }
                    return null;
                });
    }

    private static Provider google(BlogProperties.Oauth oauth) {
        return new Provider("google", "Google",
                oauth.googleClientId(), oauth.googleClientSecret(), oauth.googleRedirectUri(),
                state -> "https://accounts.google.com/o/oauth2/v2/auth"
                        + "?client_id=" + encode(oauth.googleClientId())
                        + "&redirect_uri=" + encode(oauth.googleRedirectUri())
                        + "&response_type=code"
                        + "&scope=" + encode("openid email profile")
                        + "&state=" + state
                        // 每次都让人挑账号：共用电脑时不会默默用了上一个人的
                        + "&prompt=select_account",
                "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/oauth2/v3/userinfo",
                null,
                node -> new Profile(
                        text(node, "sub"),
                        text(node, "email").toLowerCase(Locale.ROOT),
                        node.path("email_verified").asBoolean(false),
                        firstNonBlank(text(node, "name"), text(node, "email")),
                        text(node, "picture")),
                null);
    }

    /** 换 token 的请求体。两家字段名一样。 */
    public static Map<String, String> tokenForm(Provider provider, String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", provider.clientId());
        form.put("client_secret", provider.clientSecret());
        form.put("code", code);
        form.put("grant_type", "authorization_code");
        form.put("redirect_uri", provider.redirectUri());
        return form;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? "" : value.asString("");
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
