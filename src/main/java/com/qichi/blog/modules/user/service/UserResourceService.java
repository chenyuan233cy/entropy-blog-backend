package com.qichi.blog.modules.user.service;

import com.qichi.blog.common.BizException;
import com.qichi.blog.config.BlogProperties;
import com.qichi.blog.modules.upload.UploadService;
import com.qichi.blog.modules.user.UserDtos;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 用户自己的资源目录：uploads/user_resources/&lt;uid&gt;/{avatar,json,...}。
 *
 * <p>所有读写都以「当前登录用户自己的那个目录」为边界 —— 路径不是从请求里拼出来的，
 * 而是从认证身份推出来的，所以不存在越权访问别人目录的可能；
 * 传进来的相对路径再做一次归一化和前缀校验，挡掉 ../ 这类穿越。
 */
@Service
public class UserResourceService {

    /**
     * 目录布局：uploads/users/&lt;随机串&gt;/resources/{avatar,json,...}。
     * 中间那层是随机串而不是自增 id —— 头像地址是公开的，用 id 等于把用户数量和注册顺序贴出去。
     * 以后要加资源类型（备份、附件）只是多一个子目录名。
     */
    private static final String ROOT = "users";
    private static final String RESOURCES = "resources";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long MAX_EXPORT_BYTES = 2L * 1024 * 1024;

    private final BlogProperties properties;
    private final UserService userService;
    private final UploadService uploadService;

    public UserResourceService(BlogProperties properties, UserService userService, UploadService uploadService) {
        this.properties = properties;
        this.userService = userService;
        this.uploadService = uploadService;
    }

    /** 头像落到自己那一格：users/&lt;随机串&gt;/resources/avatar/xxx.png */
    public String storeAvatar(org.springframework.web.multipart.MultipartFile file, long userId) {
        String folder = ROOT + "/" + userService.resourceKey(userId) + "/" + RESOURCES + "/avatar";
        return uploadService.store(file, folder);
    }

    /** 单个资源的描述。path 是相对用户目录的路径，例如 avatar/xxx.png */
    public record ResourceVO(String path, String kind, String name, long size, String url, LocalDateTime createTime) {}

    public record Resolved(Path path, String name, long size, String mime) {}

    private Path uploadRoot() {
        return Paths.get(properties.upload().dir()).toAbsolutePath().normalize();
    }

    /** 当前登录用户自己的资源根目录。路径由身份推出来，请求里拼不出别人的。 */
    private Path userDir(long userId) {
        Path dir = uploadRoot().resolve(ROOT).resolve(userService.resourceKey(userId)).resolve(RESOURCES).normalize();
        if (!dir.startsWith(uploadRoot())) throw BizException.invalid("非法的资源目录");
        return dir;
    }

    /** 列出自己的全部资源，按类型再按时间。 */
    public List<ResourceVO> list(long userId) {
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            List<ResourceVO> items = new ArrayList<>();
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = dir.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith(".")) continue;
                String kind = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "other";
                items.add(new ResourceVO(relative, kind, path.getFileName().toString(),
                        Files.size(path), "/uploads/" + ROOT + "/" + userService.resourceKey(userId) + "/" + RESOURCES + "/" + relative,
                        LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(),
                                java.time.ZoneId.systemDefault())));
            }
            items.sort(Comparator.comparing(ResourceVO::createTime).reversed());
            return items;
        } catch (IOException ex) {
            return List.of();
        }
    }

    /**
     * 把个人资料打包成 json 放进自己的 json/ 目录，返回这个文件。
     * 打的是「用户能带走的自己那份数据」，不含密码哈希、IP 之类的内部字段。
     */
    public ResourceVO exportProfile(long userId, UserDtos.UserProfileVO profile) {
        String name = "profile-" + LocalDateTime.now().format(STAMP) + ".json";
        Path target = userDir(userId).resolve("json").resolve(name).normalize();
        if (!target.startsWith(userDir(userId))) throw BizException.invalid("非法的导出路径");

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"exportedAt\": \"").append(LocalDateTime.now()).append("\",\n");
        json.append("  \"account\": ").append(accountJson(profile)).append(",\n");
        json.append("  \"resources\": [");
        List<ResourceVO> resources = list(userId);
        for (int i = 0; i < resources.size(); i++) {
            ResourceVO item = resources.get(i);
            json.append(i == 0 ? "\n" : ",\n");
            json.append("    {\"path\": ").append(quote(item.path()))
                    .append(", \"size\": ").append(item.size())
                    .append(", \"url\": ").append(quote(item.url())).append("}");
        }
        json.append(resources.isEmpty() ? "]\n" : "\n  ]\n");
        json.append("}\n");

        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EXPORT_BYTES) throw BizException.invalid("导出内容太大了");
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException ex) {
            throw BizException.invalid("写入导出文件失败");
        }
        return new ResourceVO("json/" + name, "json", name, bytes.length,
                "/uploads/" + ROOT + "/" + userService.resourceKey(userId) + "/" + RESOURCES + "/json/" + name,
                LocalDateTime.now());
    }

    /** 解析自己的一个资源；relative 必须落在自己的目录里。 */
    public Resolved resolve(long userId, String relative) {
        if (relative == null || relative.isBlank()) throw BizException.invalid("缺少文件路径");
        Path dir = userDir(userId);
        Path path = dir.resolve(relative).normalize();
        if (!path.startsWith(dir)) throw BizException.invalid("非法的资源路径");
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw BizException.notFound("文件");
        try {
            return new Resolved(path, path.getFileName().toString(), Files.size(path), probeMime(path));
        } catch (IOException ex) {
            throw BizException.notFound("文件");
        }
    }

    private String probeMime(Path path) {
        try {
            String type = Files.probeContentType(path);
            if (type != null) return type;
        } catch (IOException ignored) {
            // 探不出来就按二进制流发，浏览器照样能存下来
        }
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private String accountJson(UserDtos.UserProfileVO profile) {
        return "{\"id\": " + (profile.id() == null ? "null" : profile.id())
                + ", \"username\": " + quote(profile.username())
                + ", \"nickname\": " + quote(profile.nickname())
                + ", \"email\": " + quote(profile.email())
                + ", \"avatar\": " + quote(profile.avatar())
                + ", \"bio\": " + quote(profile.bio())
                + ", \"location\": " + quote(profile.location())
                + ", \"website\": " + quote(profile.website())
                + ", \"role\": " + quote(profile.role())
                + ", \"lastLoginAt\": " + quote(profile.lastLoginAt() == null ? null : profile.lastLoginAt().toString())
                + "}";
    }

    /** 只做 JSON 字符串转义，够了 —— 导出的字段都是普通文本。 */
    private String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }
}
