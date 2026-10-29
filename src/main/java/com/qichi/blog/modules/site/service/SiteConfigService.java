package com.qichi.blog.modules.site.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.modules.comment.mapper.CommentMapper;
import com.qichi.blog.modules.music.mapper.MusicMapper;
import com.qichi.blog.modules.post.mapper.PostMapper;
import com.qichi.blog.modules.site.SiteDtos;
import com.qichi.blog.modules.site.entity.SiteConfig;
import com.qichi.blog.modules.site.mapper.SiteConfigMapper;
import com.qichi.blog.modules.taxonomy.mapper.CategoryMapper;
import com.qichi.blog.modules.taxonomy.mapper.TagMapper;
import com.qichi.blog.modules.user.entity.User;
import com.qichi.blog.modules.user.mapper.UserMapper;
import com.qichi.blog.security.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tunable lives in one key/value table so the admin settings screen renders itself from data
 * instead of from a hard coded form.
 */
@Service
public class SiteConfigService {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final SiteConfigMapper configMapper;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final TagMapper tagMapper;
    private final CategoryMapper categoryMapper;
    private final CommentMapper commentMapper;
    private final MusicMapper musicMapper;
    private final CacheService cache;
    private final ObjectMapper objectMapper;
    private final HtmlSanitizer sanitizer;
    /** 上传上限这些来自 application.yml，配置接口要把它一并告诉前端 */
    private final com.qichi.blog.config.BlogProperties properties;

    public SiteConfigService(SiteConfigMapper configMapper, UserMapper userMapper, PostMapper postMapper,
                             TagMapper tagMapper, CategoryMapper categoryMapper, CommentMapper commentMapper,
                             MusicMapper musicMapper, CacheService cache, ObjectMapper objectMapper,
                             HtmlSanitizer sanitizer, com.qichi.blog.config.BlogProperties properties) {
        this.properties = properties;
        this.configMapper = configMapper;
        this.userMapper = userMapper;
        this.postMapper = postMapper;
        this.tagMapper = tagMapper;
        this.categoryMapper = categoryMapper;
        this.commentMapper = commentMapper;
        this.musicMapper = musicMapper;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
    }

    // ------------------------------------------------------------------ public

    @SuppressWarnings("unchecked")
    public Map<String, Object> publicConfig() {
        String key = "blog:config:all:" + cache.configNs();
        Map<String, Object> cached = cache.get(key, Map.class);
        if (cached != null) return cached;

        Map<String, Object> root = new LinkedHashMap<>();
        for (SiteConfig row : allRows()) {
            Object value = typedValue(row);
            String configKey = row.getConfigKey();
            int dot = configKey.indexOf('.');
            if (dot < 0) {
                root.put(configKey, value);
                continue;
            }
            String prefix = configKey.substring(0, dot);
            String rest = configKey.substring(dot + 1);
            if ("social".equals(prefix) && "links".equals(rest)) {
                root.put("social", value);
                continue;
            }
            Map<String, Object> group = (Map<String, Object>) root.computeIfAbsent(prefix, k -> new LinkedHashMap<String, Object>());
            group.put(camel(rest), value);
        }
        // The author block is the account itself, not a second copy of it: editing 个人资料 in the
        // studio reaches the public site without anyone retyping the same nickname into a config row.
        root.put("author", authorProfile());
        nameAfterAuthor(root);
        // 上传上限来自 application.yml，前端要拿它做本地预检（太大了根本不用白传一遍）。
        // 写进配置里，两边就不会各说各话 —— 之前就是这里脱节，10MB 以上一律失败。
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("maxFileBytes", properties.upload().fileMaxBytes());
        root.put("upload", upload);
        cache.put(key, root, TTL);
        return root;
    }

    // ------------------------------------------------------------------ admin

    public List<SiteDtos.ConfigGroupVO> adminConfig() {
        Map<String, List<SiteDtos.ConfigItemVO>> grouped = new LinkedHashMap<>();
        for (SiteConfig row : allRows()) {
            grouped.computeIfAbsent(row.getGroupName(), key -> new ArrayList<>())
                    .add(new SiteDtos.ConfigItemVO(row.getConfigKey(), row.getConfigValue(),
                            row.getValueType(), row.getGroupName(), row.getDescription()));
        }
        List<SiteDtos.ConfigGroupVO> groups = new ArrayList<>();
        grouped.forEach((group, items) -> groups.add(new SiteDtos.ConfigGroupVO(group, items)));
        return groups;
    }

    @Transactional
    public void update(List<SiteDtos.ConfigUpdateRequest.Item> items) {
        if (items == null || items.isEmpty()) return;
        for (SiteDtos.ConfigUpdateRequest.Item item : items) {
            SiteConfig existing = configMapper.selectOne(
                    Wrappers.<SiteConfig>lambdaQuery().eq(SiteConfig::getConfigKey, item.key()));
            if (existing == null) throw BizException.notFound("配置项 " + item.key());
            existing.setConfigValue(item.value());
            configMapper.updateById(existing);
        }
        cache.bumpVersion("config");
    }

    public SiteDtos.SiteStatsVO stats() {
        return new SiteDtos.SiteStatsVO(
                postMapper.countPublished(),
                postMapper.countDrafts(),
                tagMapper.selectCount(null),
                categoryMapper.selectCount(null),
                // 走 countByStatus：它连了文章表，会把已删除/未发布文章的评论排除掉
                commentMapper.countByStatus("APPROVED"),
                commentMapper.countByStatus("PENDING"),
                musicMapper.selectCount(null),
                postMapper.sumPublishedWords(),
                startAt());
    }

    /**
     * 建站时间，精确到分钟。「已运行」按它算，所以不能再拿一个年份凑数 —— 同一年的 1 月 1 日
     * 和 12 月 31 日差一年，那样算出来的天数没有意义。
     */
    public LocalDateTime startAt() {
        String raw = requireString("site.start_at", "");
        if (raw != null && !raw.isBlank()) {
            try {
                return LocalDateTime.parse(raw.trim().replace(" ", "T"));
            } catch (DateTimeParseException ignored) {
                // 填错了就当作没填，前端会退回「今天上线」
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ lookups

    public boolean requireBool(String configKey, boolean fallback) {
        SiteConfig row = one(configKey);
        if (row == null || row.getConfigValue() == null) return fallback;
        return "true".equalsIgnoreCase(row.getConfigValue().trim());
    }

    public int requireInt(String configKey, int fallback) {
        SiteConfig row = one(configKey);
        if (row == null || row.getConfigValue() == null) return fallback;
        try {
            return Integer.parseInt(row.getConfigValue().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public String requireString(String configKey, String fallback) {
        SiteConfig row = one(configKey);
        return row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()
                ? fallback : row.getConfigValue();
    }

    /** The single author account, the one every public byline is rendered from. */
    public User author() {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, "ADMIN")
                .orderByAsc(User::getId)
                .last("limit 1"));
    }

    public String authorNickname() {
        User user = author();
        if (user == null) return "";
        return user.getNickname() == null || user.getNickname().isBlank() ? user.getUsername() : user.getNickname();
    }

    /**
     * An unnamed site borrows the author's name. Filling in 个人资料 is the one thing every owner
     * does first, and the alternative is a front page that greets visitors with 「未命名站点」.
     * Typing a name into 站点配置 always wins.
     */
    @SuppressWarnings("unchecked")
    private void nameAfterAuthor(Map<String, Object> root) {
        Map<String, Object> site = (Map<String, Object>) root.get("site");
        Object current = site == null ? null : site.get("name");
        if (current != null && !String.valueOf(current).isBlank()) return;

        User user = author();
        String nickname = user == null ? null : user.getNickname();
        if (nickname == null || nickname.isBlank()) return;

        Map<String, Object> target = site == null
                ? (Map<String, Object>) root.computeIfAbsent("site", k -> new LinkedHashMap<String, Object>())
                : site;
        target.put("name", nickname);
    }

    private Map<String, Object> authorProfile() {
        User user = author();
        Map<String, Object> profile = new LinkedHashMap<>();
        if (user == null) return profile;
        profile.put("nickname", user.getNickname());
        profile.put("bio", user.getBio());
        profile.put("avatar", user.getAvatar());
        profile.put("location", user.getLocation());
        profile.put("email", user.getEmail());
        profile.put("website", user.getWebsite());
        return profile;
    }

    private SiteConfig one(String configKey) {
        return configMapper.selectOne(Wrappers.<SiteConfig>lambdaQuery().eq(SiteConfig::getConfigKey, configKey));
    }

    private List<SiteConfig> allRows() {
        return configMapper.selectList(Wrappers.<SiteConfig>lambdaQuery().orderByAsc(SiteConfig::getId));
    }

    private Object typedValue(SiteConfig row) {
        String raw = row.getConfigValue();
        String type = row.getValueType() == null ? "STRING" : row.getValueType().toUpperCase();
        if (raw == null) return null;
        return switch (type) {
            case "NUMBER" -> {
                // Integer, not Long: boxed Longs leave this API as strings (see JsonConfig),
                // and a JSON string would break arithmetic on the other side.
                try {
                    yield Integer.parseInt(raw.trim());
                } catch (NumberFormatException ex) {
                    yield raw;
                }
            }
            case "BOOL" -> "true".equalsIgnoreCase(raw.trim());
            case "JSON" -> {
                try {
                    yield objectMapper.readValue(raw, Object.class);
                } catch (Exception ex) {
                    yield List.of();
                }
            }
            case "TEXT" -> sanitizer.plain(raw);
            default -> sanitizer.stripBrackets(raw);
        };
    }

    private String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
