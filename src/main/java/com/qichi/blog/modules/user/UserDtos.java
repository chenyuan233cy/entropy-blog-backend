package com.qichi.blog.modules.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class UserDtos {

    private UserDtos() {}

    @Schema(description = "登录请求")
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {}

    @Schema(description = "密码验证通过后的结果：要么直接登录，要么等邮箱验证码")
    public record LoginStepVO(
            /** true = 还要一次邮箱验证码，session 为空；false = 已经登录 */
            boolean needCode,
            /** 第二步凭它认人，十分钟内有效 */
            String ticket,
            /** 验证码发到哪儿了，形如 1***@qq.com */
            String emailHint,
            SessionVO session) {}

    /**
     * 对外唯一的「登录结果」形状。
     *
     * <p>两个令牌只通过 HttpOnly cookie 下发，响应体里一个都不出现 —— 前端脚本读不到，
     * XSS 也偷不走。{@link LoginResult} 只是服务端的内部载体。
     */
    public record SessionVO(UserProfileVO user, long expiresIn) {

        public static SessionVO of(LoginResult result) {
            return result == null ? null : new SessionVO(result.user(), result.expiresIn());
        }
    }

    @Schema(description = "第二步：提交邮箱验证码")
    public record LoginVerifyRequest(
            @NotBlank(message = "缺少登录票据") String ticket,
            @NotBlank(message = "请填写验证码") String code) {}

    @Schema(description = "重发登录验证码")
    public record LoginTicketRequest(@NotBlank(message = "缺少登录票据") String ticket) {}

    @Schema(description = "注册请求")
    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 3, max = 20, message = "用户名 3 到 20 位")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "用户名只能包含字母、数字、下划线、连字符")
            String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 64, message = "密码长度需在 8 到 64 位之间")
            String password,
            @NotBlank(message = "昵称不能为空")
            @Size(min = 1, max = 20, message = "昵称最长 20 字")
            String nickname,
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 120)
            String email) {}

    @Schema(description = "要一个邮箱验证码")
    public record EmailCodeRequest(
            @NotBlank(message = "请填写邮箱") String email) {}

    @Schema(description = "用邮箱验证码注册。用户名和昵称都由服务端生成")
    public record EmailRegisterRequest(
            @NotBlank(message = "请填写邮箱") String email,
            @NotBlank(message = "请填写验证码") String code,
            @NotBlank(message = "请设置密码")
            @Size(min = 8, max = 64, message = "密码长度需在 8 到 64 位之间") String password) {}

    @Schema(description = "社交链接")
    public record SocialLink(String name, String url, String icon) {}

    @Schema(description = "当前用户档案")
    public record UserProfileVO(
            Long id, String username, String nickname, String email, String avatar,
            String bio, String location, String website, List<SocialLink> socialLinks,
            String role, LocalDateTime lastLoginAt,
            /** 设过真密码没有。第三方自动建号是 false —— 前端据此显示「设置密码」而不是「修改密码」 */
            boolean passwordSet,
            /** 已绑定的第三方平台，如 ["github"]。空数组表示只能靠密码登录 */
            List<String> oauthProviders) {}

    @Schema(description = "登录结果")
    public record LoginResult(
            String accessToken, String refreshToken, String tokenType, long expiresIn,
            UserProfileVO user) {}

    @Schema(description = "资料更新请求")
    public record ProfileUpdateRequest(
            @Size(max = 50, message = "昵称最长 50 字") String nickname,
            @Email(message = "邮箱格式不正确") String email,
            @Size(max = 500) String avatar,
            @Size(max = 500, message = "简介最长 500 字") String bio,
            @Size(max = 80) String location,
            @Size(max = 255) String website,
            List<SocialLink> socialLinks) {}

    @Schema(description = "修改密码请求")
    public record PasswordChangeRequest(
            /** 第三方自动建号的账号没有真密码，这一项留空 */
            String oldPassword,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 64, message = "新密码长度需在 8 到 64 位之间") String newPassword) {}
}
