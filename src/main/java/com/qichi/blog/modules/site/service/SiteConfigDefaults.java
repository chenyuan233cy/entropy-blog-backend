package com.qichi.blog.modules.site.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.modules.site.entity.SiteConfig;
import com.qichi.blog.modules.site.mapper.SiteConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The admin settings screen builds itself from the site_config table, so an empty table means an
 * empty screen and a site that cannot say its own name. This inserts every key the app reads, and
 * only the keys that are missing, so an existing value is never overwritten and a fresh database
 * becomes usable the first time the backend boots.
 */
@Component
public class SiteConfigDefaults implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SiteConfigDefaults.class);

    private record Preset(String key, String value, String type, String group, String description) {}

    /**
     * 默认屏蔽词。只放两种情况：明确的脏话，和一眼就是广告的词。
     * 「兼职」「推广」「发票」这类正常词故意不放 —— 命中是直接拒收，误伤比漏过更烦人。
     */
    private static final String DEFAULT_BLOCKED_WORDS = String.join(",",
            // 脏话
            "操你妈", "草泥马", "操你", "草你妈", "日你妈", "干你妈", "你妈死", "死全家", "妈了个逼",
            "傻逼", "傻B", "傻b", "沙比", "煞笔", "煞逼", "二逼", "尼玛", "泥马", "卧槽", "我操", "我草",
            "贱人", "贱货", "婊子", "妓女", "卖淫", "招嫖", "约炮", "畜生", "杂种", "王八蛋", "狗杂种",
            "滚你妈", "你他妈", "去你妈", "屌你",
            // 硬广告
            "加微信", "加v信", "代刷", "刷单", "博彩", "赌博", "私聊我", "出售账号", "办证", "开发票",
            // 英文
            "fuck", "shit", "bitch", "asshole", "cunt", "nigger", "porn", "casino", "viagra");

    private final SiteConfigMapper configMapper;
    private final CacheService cache;

    public SiteConfigDefaults(SiteConfigMapper configMapper, CacheService cache) {
        this.configMapper = configMapper;
        this.cache = cache;
    }

    @Override
    public void run(ApplicationArguments args) {
        int year = LocalDate.now().getYear();
        String copyright = "© " + year;

        Map<String, Preset> presets = new LinkedHashMap<>();
        add(presets, "site.name", "", "STRING", "站点", "站点名称，出现在侧栏、页脚与浏览器标题；留空则用作者昵称");
        add(presets, "site.tagline", "写字的地方", "STRING", "站点", "副标题，显示在站点名下方");
        add(presets, "site.description", "一本可以待着的个人刊物：长文、笔记与偶尔的音乐。", "TEXT", "站点", "站点描述，用于首页开场的打字机与搜索引擎摘要");
        add(presets, "site.logo", "", "STRING", "站点",
                "站点图标。正方形、至少 64×64，PNG / SVG 最好。侧栏、页脚、浏览器标签页都用它；留空就用站点名首字");
        add(presets, "site.keywords", "博客,随笔,前端,写作", "STRING", "站点", "搜索关键词，英文逗号分隔");
        add(presets, "site.copyright", copyright, "STRING", "站点", "页脚版权文字");
        add(presets, "site.icp", "", "STRING", "站点", "备案号，留空则不显示");
        add(presets, "site.start_at", LocalDateTime.now().withSecond(0).withNano(0).toString(), "STRING", "站点",
                "建站时间，精确到分钟，例如 2026-09-22 15:54（也可以写 2026-09-22T15:54）。「已运行」按它算");
        add(presets, "seo.og_image", "", "STRING", "SEO", "分享卡片大图地址，留空则用默认卡片");
        add(presets, "social.links", "[]", "JSON", "社交",
                "社交链接数组。示例：" +
                "[{\"name\":\"GitHub\",\"url\":\"https://github.com/you\",\"icon\":\"github\"}]"
                + " —— 键名是 name / url / icon（冒号后面才是值），icon 可写 github / x / twitter / telegram / wechat / rss / mail / qq，其它值显示通用链接图标");
        add(presets, "module.articles", "true", "BOOL", "模块", "文章列表与文章详情");
        add(presets, "module.archive", "true", "BOOL", "模块", "归档");
        add(presets, "module.tags", "true", "BOOL", "模块", "标签页与标签详情");
        add(presets, "module.music", "true", "BOOL", "模块", "歌单");
        add(presets, "module.files", "true", "BOOL", "模块", "文件（上传下载）");
        // 工具页只是个占位，默认关着；想用再打开
        add(presets, "module.tools", "false", "BOOL", "模块", "工具（占位页，默认关闭）");
        add(presets, "module.sponsor", "true", "BOOL", "模块", "赞助名单");
        add(presets, "module.search", "true", "BOOL", "模块", "搜索");
        add(presets, "module.about", "true", "BOOL", "模块", "关于页");
        add(presets, "about.note", "", "TEXT", "关于",
                "关于页正文的最后一段（比如这个站用了什么技术）。留空就不显示");
        add(presets, "sponsor.note", "", "TEXT", "赞助",
                "赞助方式：想怎么被赞助就写在这儿。会显示在关于页，也会显示在赞助页「赞助」标题的右侧。支持多行");
        // 总闸和「有没有填密钥」是两回事：填了也可以临时关掉（平台被墙、正在排查）。
        // 管理界面在「登录方式」那一页（/manage/logins）
        add(presets, "auth.oauth_github", "true", "BOOL", "登录", "要不要开放 GitHub 登录。关掉后登录页不显示这个入口，硬敲地址也进不来");
        add(presets, "auth.oauth_google", "true", "BOOL", "登录", "要不要开放 Google 登录。同上");
        add(presets, "auth.email_code_login", "true", "BOOL", "登录",
                "账号密码登录要不要再收一次邮箱验证码：开着的话，就算密码被人知道了也进不来。"
                        + "关掉就只剩密码这一道（不建议）");
        add(presets, "comment.require_email", "true", "BOOL", "评论", "访客评论是否必填邮箱");
        add(presets, "comment.auto_approve", "true", "BOOL", "评论", "评论是否直接发布。关掉就全部进待审队列");
        add(presets, "comment.blocked_words", DEFAULT_BLOCKED_WORDS, "TEXT", "评论",
                "评论正文里出现这些词就直接拒收并提示对方，不进任何队列。中英文逗号分隔；留空则不过滤。"
                        + "默认只覆盖明确脏话和硬广告，误伤了你就在这儿删掉那个词");

        int created = 0;
        for (Preset preset : presets.values()) {
            Long existing = configMapper.selectCount(
                    Wrappers.<SiteConfig>lambdaQuery().eq(SiteConfig::getConfigKey, preset.key()));
            if (existing != null && existing > 0) continue;

            SiteConfig row = new SiteConfig();
            row.setConfigKey(preset.key());
            row.setConfigValue(preset.value());
            row.setValueType(preset.type());
            row.setGroupName(preset.group());
            row.setDescription(preset.description());
            configMapper.insert(row);
            created++;
        }
        if (created > 0) {
            // the public config is cached by namespace version, and it may already hold the empty map
            cache.bumpVersion("config");
            log.info("site_config: {} 项默认配置已补齐", created);
        }
    }

    private void add(Map<String, Preset> presets, String key, String value, String type, String group, String description) {
        presets.put(key, new Preset(key, value, type, group, description));
    }
}
