package com.qichi.blog.modules.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.common.ErrorCode;
import com.qichi.blog.modules.user.UserDtos;
import com.qichi.blog.modules.user.entity.User;
import com.qichi.blog.modules.user.entity.UserOauth;
import com.qichi.blog.modules.user.mapper.UserMapper;
import com.qichi.blog.security.HtmlSanitizer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final com.qichi.blog.modules.user.mapper.UserOauthMapper oauthMapper;
    private final PasswordEncoder passwordEncoder;
    private final HtmlSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final CacheService cache;
    private final com.qichi.blog.security.TokenVersionService tokenVersions;

    public UserService(UserMapper userMapper, com.qichi.blog.modules.user.mapper.UserOauthMapper oauthMapper,
                       PasswordEncoder passwordEncoder,
                       HtmlSanitizer sanitizer, ObjectMapper objectMapper, CacheService cache,
                       com.qichi.blog.security.TokenVersionService tokenVersions) {
        this.userMapper = userMapper;
        this.oauthMapper = oauthMapper;
        this.passwordEncoder = ${THIS_PASSWORDENCODER:}
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.tokenVersions = ${THIS_TOKENVERSIONS:}
    }

    /** 站点是否还一个账号都没有 —— 空库时允许创建第一个（站长）账号。 */
    public boolean hasAnyUser() {
        return userMapper.selectCount(Wrappers.<User>lambdaQuery()) > 0;
    }

    public User findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    /**
     * Creates a reader account. Registration never picks its own role: everyone who signs up from
     * the public site is a plain USER, and only /studio can see past that.
     */
    @Transactional
    public User createAccount(String username, String rawPassword, String nickname, String email, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(sanitizer.plain(nickname));
        user.setEmail(sanitizer.plain(email));
        user.setRole(role);
        user.setStatus(1);
        user.setSocialLinks("[]");
        userMapper.insert(user);
        return user;
    }

    /** 评论列表要按 user_id 批量取账号，别一条评论查一次。 */
    public List<User> findAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return userMapper.selectByIds(ids);
    }

    // ------------------------------------------------------------------ 第三方绑定

    /** 第三方身份属于哪个账号。同一个 (provider, openId) 只可能有一条。 */
    public User findByOauth(String provider, String openId) {
        UserOauth link = oauthMapper.selectOne(Wrappers.<UserOauth>lambdaQuery()
                .eq(UserOauth::getProvider, provider)
                .eq(UserOauth::getOpenId, openId)
                .last("limit 1"));
        return link == null ? null : requireById(link.getUserId());
    }

    /** 这个账号绑了哪几个平台。 */
    public List<String> oauthProvidersOf(Long userId) {
        return oauthMapper.selectList(Wrappers.<UserOauth>lambdaQuery().eq(UserOauth::getUserId, userId))
                .stream().map(UserOauth::getProvider).toList();
    }

    /**
     * 把第三方身份挂到账号上。
     *
     * <p>头像只在账号还没有头像时才填 —— 用户自己传的那张不该被第三方登录悄悄换掉。
     */
    @Transactional
    public void bindOauth(Long userId, String provider, String openId, String email, String nickname, String avatar) {
        User existing = userMapper.selectById(userId);
        if (existing != null && (existing.getAvatar() == null || existing.getAvatar().isBlank())
                && avatar != null && !avatar.isBlank()) {
            User patch = new User();
            patch.setId(userId);
            patch.setAvatar(sanitizer.plain(avatar));
            userMapper.updateById(patch);
        }
        UserOauth link = new UserOauth();
        link.setUserId(userId);
        link.setProvider(provider);
        link.setOpenId(openId);
        link.setEmail(email == null ? null : sanitizer.plain(email));
        link.setNickname(nickname == null ? null : sanitizer.plain(nickname));
        link.setCreateTime(java.time.LocalDateTime.now());
        oauthMapper.insert(link);
    }

    /** 管理界面要看「这个平台有多少人绑着」。 */
    public long countOauthBindings(String provider) {
        return oauthMapper.selectCount(Wrappers.<UserOauth>lambdaQuery().eq(UserOauth::getProvider, provider));
    }

    public void unbindOauth(Long userId, String provider) {
        oauthMapper.delete(Wrappers.<UserOauth>lambdaQuery()
                .eq(UserOauth::getUserId, userId)
                .eq(UserOauth::getProvider, provider));
    }

    /** 第三方自动建的账号密码是随机占位，那种账号不许把最后一个登录方式解掉。 */
    public boolean hasPassword(User user) {
        return user.getPasswordSet() == null || user.getPasswordSet() == 1;
    }

    /**
     * 第三方登录第一次来的账号。用户名和昵称同样不由用户填 ——
     * 他点「用 GitHub 登录」的时候并没有打算再想一个用户名。
     */
    @Transactional
    public User createOauthAccount(String provider, String openId, String email, String avatar, String displayName) {
        User user = new User();
        user.setUsername(uniqueUsername());
        // 密码是随机占位，密码登录那条路走不通；password_set=0 让「解绑」知道这人还没有真密码
        user.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
        user.setPasswordSet(0);
        user.setNickname(nicknameFrom(displayName));
        user.setEmail(email == null || email.isBlank() ? null : sanitizer.plain(email));
        user.setAvatar(avatar == null || avatar.isBlank() ? null : avatar);
        user.setRole(com.qichi.blog.security.LoginUser.ROLE_USER);
        user.setStatus(1);
        user.setSocialLinks("[]");
        userMapper.insert(user);
        return user;
    }

    private String uniqueUsername() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = "u" + Long.toString(Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong()), 36);
            if (candidate.length() > 12) candidate = candidate.substring(0, 12);
            if (findByUsername(candidate) == null) return candidate;
        }
        throw new BizException(com.qichi.blog.common.ErrorCode.INTERNAL, "生成用户名失败，请重试");
    }

    /** 有 GitHub 昵称就用它，没有就给「用户+随机数」。太长的截断。 */
    private String nicknameFrom(String displayName) {
        String name = displayName == null ? "" : sanitizer.plain(displayName).trim();
        if (name.isBlank()) name = "用户" + (100000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(900000));
        return name.length() > 50 ? name.substring(0, 50) : name;
    }

    /** 邮箱注册的账号靠邮箱找回，所以邮箱也得能查。 */
    public User findByEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getEmail, email.trim().toLowerCase(java.util.Locale.ROOT))
                .last("limit 1"));
    }

    public User requireById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) throw BizException.notFound("用户");
        return user;
    }

    /**
     * 用户资源目录的名字。刻意用随机串而不是自增 id —— 这个目录名会出现在公开的头像地址里，
     * 用 id 等于把「站上有多少用户、按注册顺序都是谁」贴在外面。第一次用到时才生成。
     */
    public String resourceKey(Long userId) {
        User user = requireById(userId);
        String existing = user.getResourceKey();
        if (existing != null && !existing.isBlank()) return existing;

        String key = java.util.UUID.randomUUID().toString().replace("-", "");
        User patch = new User();
        patch.setId(userId);
        patch.setResourceKey(key);
        userMapper.updateById(patch);
        return key;
    }

    public List<UserDtos.SocialLink> readSocialLinks(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            UserDtos.SocialLink[] links = objectMapper.readValue(json, UserDtos.SocialLink[].class);
            return List.of(links);
        } catch (Exception ex) {
            return List.of();
        }
    }

    public UserDtos.UserProfileVO toProfile(User user) {
        return new UserDtos.UserProfileVO(
                user.getId(), user.getUsername(), user.getNickname(), user.getEmail(), user.getAvatar(),
                user.getBio(), user.getLocation(), user.getWebsite(), readSocialLinks(user.getSocialLinks()),
                user.getRole(), user.getLastLoginAt(),
                hasPassword(user), oauthProvidersOf(user.getId()));
    }

    public void touchLastLogin(Long userId) {
        User patch = new User();
        patch.setId(userId);
        patch.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(patch);
    }

    @Transactional
    public UserDtos.UserProfileVO updateProfile(Long userId, UserDtos.ProfileUpdateRequest request) {
        // 邮箱是账号归属的凭据（登录、找回、第三方认领都靠它），不能两个账号共用一个。
        // 数据库上也加了唯一索引，这里先查一次是为了给一句人话，而不是抛 SQL 错误。
        String email = request.email() == null ? null : sanitizer.plain(request.email());
        if (email != null && !email.isBlank()) {
            User taken = findByEmail(email);
            if (taken != null && !taken.getId().equals(userId)) {
                throw new BizException(ErrorCode.CONFLICT,
                        "这个邮箱已经属于另一个账号（" + taken.getUsername() + "）了。一个邮箱只能绑一个账号。");
            }
        } else {
            email = null;
        }

        User patch = new User();
        patch.setId(userId);
        patch.setNickname(request.nickname() == null ? null : sanitizer.plain(request.nickname()));
        patch.setEmail(email);
        patch.setAvatar(request.avatar() == null ? null : sanitizer.stripBrackets(request.avatar().trim()));
        patch.setBio(request.bio() == null ? null : sanitizer.plain(request.bio()));
        patch.setLocation(request.location() == null ? null : sanitizer.plain(request.location()));
        patch.setWebsite(request.website() == null ? null : sanitizer.stripBrackets(request.website().trim()));
        if (request.socialLinks() != null) {
            patch.setSocialLinks(objectMapper.writeValueAsString(request.socialLinks()));
        }
        userMapper.updateById(patch);
        // the public author block is derived from this row, so the cached copy has to go
        cache.bumpVersion("config");
        return toProfile(requireById(userId));
    }

    @Transactional
    public void changePassword(Long userId, UserDtos.PasswordChangeRequest request) {
        User user = requireById(userId);
        // 第三方自动建号的账号没有真密码（占位是随机的），没法「填原密码」—— 直接让他设一个。
        // 这也是他解绑唯一第三方账号之前必须走的一步。
        if (hasPassword(user) && !passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "原密码不正确");
        }
        User patch = new User();
        patch.setId(userId);
        patch.setPassword(passwordEncoder.encode(request.newPassword()));
        // 从此这个人有真密码了：第三方全解绑也不会把自己关在门外
        patch.setPasswordSet(1);
        userMapper.updateById(patch);
        // 改密码等于「所有旧会话作废」：别人手上的令牌不能再用，当前这台设备也要重新登录
        tokenVersions.bump(userId);
    }


}
