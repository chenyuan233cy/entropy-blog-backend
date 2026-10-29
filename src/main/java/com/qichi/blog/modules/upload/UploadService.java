package com.qichi.blog.modules.upload;

import com.qichi.blog.common.BizException;
import com.qichi.blog.common.ErrorCode;
import com.qichi.blog.config.BlogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 图片落地：校验类型与大小，随机文件名写进 uploads，返回可直接用的 /uploads/... 地址。
 * 后台写文章传封面、普通读者换头像，走的都是这里。
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final BlogProperties properties;

    public UploadService(BlogProperties properties) {
        this.properties = properties;
    }

    public String store(MultipartFile file) {
        return store(file, null);
    }

    /**
     * 用户自己的资源按 uid 分目录：user_resources/&lt;uid&gt;/avatar/xxx.png。
     * 目录结构本身就是隔离边界 —— 列出、导出、下载都以这个目录为界，
     * 将来加 json/、backup/ 只需要多传一个子目录名。
     */
    public String storeAvatar(MultipartFile file, long userId) {
        return store(file, "user_resources/" + userId + "/avatar");
    }

    /**
     * @param folder 相对 uploads 的子目录；传 null 就是原来的 年/月 结构（文章封面走这条）
     */
    public String store(MultipartFile file, String folder) {
        BlogProperties.Upload config = properties.upload();
        if (file == null || file.isEmpty()) throw BizException.invalid("请选择要上传的文件");
        if (file.getSize() > config.maxBytes()) {
            throw BizException.invalid("文件超过 " + (config.maxBytes() / 1024 / 1024) + "MB 限制");
        }

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        List<String> allowed = config.allowedExtensions();
        if (allowed == null || !allowed.contains(extension)) {
            throw BizException.invalid("只允许上传这些格式：" + String.join(", ", allowed == null ? List.of() : allowed));
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw BizException.invalid("文件类型不是图片");
        }

        // 文件名一律自己生成，用户传什么名字都进不了文件系统
        String name = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String relative = folder == null || folder.isBlank()
                ? LocalDate.now().format(YEAR_MONTH) + "/" + name
                : folder.replaceAll("^/+|/+$", "") + "/" + name;
        // 必须是绝对路径：MultipartFile.transferTo 对相对路径是按 servlet 临时目录解析的，
        // 不是按进程工作目录，写相对路径会直接 IOException。
        Path root = Paths.get(config.dir()).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw BizException.invalid("非法的上传路径");
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
        } catch (IOException ex) {
            log.error("upload failed for {}", relative, ex);
            throw new BizException(ErrorCode.INTERNAL, "保存文件失败");
        }
        return "/uploads/" + relative;
    }

    /**
     * 直接把字节写进 uploads。第三方登录拉下来的头像走这条 ——
     * 头像必须存在我们自己这里：外部地址（比如 avatars.githubusercontent.com）
     * 既过不了前端的图片白名单，在国内还经常连不上。
     */
    public String storeBytes(byte[] content, String folder) {
        if (content == null || content.length == 0) throw BizException.invalid("内容为空");
        BlogProperties.Upload config = properties.upload();
        if (content.length > config.maxBytes()) throw BizException.invalid("图片太大了");

        String extension = sniffImageExtension(content);
        if (extension == null) throw BizException.invalid("不是能识别的图片格式");

        String relative = folder.replaceAll("^/+|/+$", "") + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path root = Paths.get(config.dir()).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw BizException.invalid("非法的上传路径");
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException ex) {
            log.error("failed to write {}", relative, ex);
            throw new BizException(ErrorCode.INTERNAL, "保存文件失败");
        }
        return "/uploads/" + relative;
    }

    /** 看头几个字节认格式。认不出来就不存 —— 免得存下扩展名和内容对不上的文件。 */
    private String sniffImageExtension(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "png";
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return "jpg";
        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "gif";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
        return null;
    }

    /**
     * 工具页的文件落地。和图片共用目录，只是上限更大、允许的类型更多（黑名单制）。
     * 文件名照样自己生成，用户传什么名字都进不了文件系统。
     */
    public Stored storeDocument(MultipartFile file) {
        BlogProperties.Upload config = properties.upload();
        if (file == null || file.isEmpty()) throw BizException.invalid("请选择要上传的文件");
        long max = config.fileMaxBytes() > 0 ? config.fileMaxBytes() : config.maxBytes();
        if (file.getSize() > max) {
            throw BizException.invalid("文件超过 " + (max / 1024 / 1024) + "MB 限制");
        }

        String original = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();
        String extension = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!config.fileAllowed(extension)) {
            throw BizException.invalid("出于安全考虑，不允许上传 ." + extension + " 文件");
        }

        String relative = "files/" + LocalDate.now().format(YEAR_MONTH) + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + (extension.isEmpty() ? "" : "." + extension);
        Path root = Paths.get(config.dir()).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw BizException.invalid("非法的上传路径");
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
        } catch (IOException ex) {
            log.error("document upload failed for {}", relative, ex);
            throw new BizException(ErrorCode.INTERNAL, "保存文件失败");
        }
        return new Stored("/uploads/" + relative, target, safeName(original), extension, file.getSize());
    }

    /** 落盘结果：对外地址、真实路径、清洗过的原文件名、后缀、大小。 */
    public record Stored(String url, Path path, String name, String extension, long size) {}

    /** 文件名只留安全字符 —— 它最终会进 Content-Disposition 响应头。 */
    public static String safeName(String raw) {
        String name = raw == null ? "未命名文件" : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) name = "未命名文件";
        return name.length() > 180 ? name.substring(0, 180) : name;
    }
}
