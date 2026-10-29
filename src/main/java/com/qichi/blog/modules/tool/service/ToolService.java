package com.qichi.blog.modules.tool.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.common.BizException;
import com.qichi.blog.config.BlogProperties;
import com.qichi.blog.modules.tool.ToolDtos;
import com.qichi.blog.modules.tool.entity.ToolNode;
import com.qichi.blog.modules.tool.mapper.ToolNodeMapper;
import com.qichi.blog.modules.upload.UploadService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具页：一个两层的目录树（文件夹套文件夹，文件挂在文件夹下）。
 *
 * <p>删除一律是逻辑删除，磁盘上的文件保留 —— 文章里可能引用着它，物理删除会让链接变成死链。
 */
@Service
public class ToolService {

    private static final long ROOT = 0L;
    private static final int MAX_DEPTH = 8;

    private final ToolNodeMapper mapper;
    private final UploadService uploadService;
    private final BlogProperties properties;

    public ToolService(ToolNodeMapper mapper, UploadService uploadService, BlogProperties properties) {
        this.mapper = mapper;
        this.uploadService = uploadService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 读

    public ToolDtos.Listing listing(Long parentId) {
        long parent = parentId == null ? ROOT : parentId;
        if (parent != ROOT) requireFolder(parent);

        List<ToolNode> nodes = mapper.selectList(
                Wrappers.<ToolNode>lambdaQuery().eq(ToolNode::getParentId, parent));
        // 文件夹在前，同类按名字排：中文按拼音顺序才符合直觉
        nodes.sort(Comparator
                .comparing((ToolNode node) -> ToolNode.FOLDER.equals(node.getKind()) ? 0 : 1)
                .thenComparing(ToolNode::getName, java.text.Collator.getInstance(java.util.Locale.CHINA)));

        List<Long> folderIds = nodes.stream()
                .filter(node -> ToolNode.FOLDER.equals(node.getKind()))
                .map(ToolNode::getId).toList();
        // 哪些文件夹里还有东西，前端要据此决定是否显示展开箭头
        Map<Long, Long> childCounts = folderIds.isEmpty() ? Map.of()
                : mapper.selectList(Wrappers.<ToolNode>lambdaQuery().in(ToolNode::getParentId, folderIds))
                .stream().collect(Collectors.groupingBy(ToolNode::getParentId, Collectors.counting()));

        List<ToolDtos.ToolNodeVO> items = new ArrayList<>();
        long folders = 0;
        long files = 0;
        long size = 0;
        for (ToolNode node : nodes) {
            boolean folder = ToolNode.FOLDER.equals(node.getKind());
            if (folder) folders++;
            else {
                files++;
                size += node.getSize() == null ? 0 : node.getSize();
            }
            items.add(new ToolDtos.ToolNodeVO(
                    node.getId(), node.getParentId(), node.getKind(), node.getName(), node.getUrl(),
                    node.getSize() == null ? 0 : node.getSize(), node.getMime(), node.getExt(),
                    node.getDescription(), node.getDownloadCount() == null ? 0 : node.getDownloadCount(),
                    folder && childCounts.getOrDefault(node.getId(), 0L) > 0,
                    node.getCreateTime()));
        }
        return new ToolDtos.Listing(crumbs(parent), items, folders, files, size);
    }

    /** 全部文件（扁平），写作时挑文件用。 */
    public List<ToolDtos.ToolNodeVO> allFiles() {
        List<ToolNode> files = mapper.selectList(
                Wrappers.<ToolNode>lambdaQuery().eq(ToolNode::getKind, ToolNode.FILE).orderByDesc(ToolNode::getCreateTime));
        return files.stream()
                .map(node -> new ToolDtos.ToolNodeVO(
                        node.getId(), node.getParentId(), node.getKind(), node.getName(), node.getUrl(),
                        node.getSize() == null ? 0 : node.getSize(), node.getMime(), node.getExt(),
                        node.getDescription(), node.getDownloadCount() == null ? 0 : node.getDownloadCount(),
                        false, node.getCreateTime()))
                .toList();
    }

    /** 从当前目录一路往上，做成面包屑。 */
    private List<ToolDtos.Crumb> crumbs(long parentId) {
        List<ToolDtos.Crumb> path = new ArrayList<>();
        long cursor = parentId;
        int guard = 0;
        while (cursor != ROOT && guard++ < MAX_DEPTH) {
            ToolNode node = mapper.selectById(cursor);
            if (node == null) break;
            path.add(0, new ToolDtos.Crumb(node.getId(), node.getName()));
            cursor = node.getParentId() == null ? ROOT : node.getParentId();
        }
        return path;
    }

    // ------------------------------------------------------------------ 写

    @Transactional
    public ToolDtos.ToolNodeVO createFolder(ToolDtos.FolderRequest request) {
        long parent = request.parentId() == null ? ROOT : request.parentId();
        if (parent != ROOT) requireFolder(parent);
        if (depthOf(parent) >= MAX_DEPTH) throw BizException.invalid("目录层数太深了");
        String name = request.name().trim();
        requireUniqueName(parent, name, null);

        ToolNode node = new ToolNode();
        node.setParentId(parent);
        node.setKind(ToolNode.FOLDER);
        node.setName(name);
        node.setSize(0L);
        node.setDownloadCount(0);
        node.setSortOrder(0);
        node.setCreateTime(LocalDateTime.now());
        mapper.insert(node);
        return new ToolDtos.ToolNodeVO(node.getId(), parent, ToolNode.FOLDER, name, null, 0, null, null,
                null, 0, false, node.getCreateTime());
    }

    @Transactional
    public ToolDtos.ToolNodeVO upload(MultipartFile file, Long parentId, String description) {
        long parent = parentId == null ? ROOT : parentId;
        if (parent != ROOT) requireFolder(parent);
        UploadService.Stored stored = uploadService.storeDocument(file);

        ToolNode node = new ToolNode();
        node.setParentId(parent);
        node.setKind(ToolNode.FILE);
        node.setName(uniqueFileName(parent, stored.name()));
        node.setUrl(stored.url());
        node.setSize(stored.size());
        node.setMime(file.getContentType());
        node.setExt(stored.extension());
        node.setDescription(description == null || description.isBlank() ? null : description.trim());
        node.setDownloadCount(0);
        node.setSortOrder(0);
        node.setCreateTime(LocalDateTime.now());
        mapper.insert(node);
        return new ToolDtos.ToolNodeVO(node.getId(), parent, ToolNode.FILE, node.getName(), node.getUrl(),
                node.getSize(), node.getMime(), node.getExt(), node.getDescription(), 0, false, node.getCreateTime());
    }

    @Transactional
    public void rename(Long id, ToolDtos.RenameRequest request) {
        ToolNode node = require(id);
        String name = request.name().trim();
        requireUniqueName(node.getParentId(), name, id);
        ToolNode patch = new ToolNode();
        patch.setId(id);
        patch.setName(name);
        patch.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().trim());
        mapper.updateById(patch);
    }

    /** 逻辑删除整棵子树，磁盘文件保留。 */
    @Transactional
    public int delete(Long id) {
        require(id);
        int removed = deleteRecursive(id);
        // 原来这里只删了子节点，自己那行没删 —— 所以点了「删除」界面纹丝不动
        mapper.deleteById(id);
        return removed + 1;
    }

    private int deleteRecursive(Long id) {
        List<ToolNode> children = mapper.selectList(
                Wrappers.<ToolNode>lambdaQuery().eq(ToolNode::getParentId, id));
        int removed = 0;
        for (ToolNode child : children) {
            removed += deleteRecursive(child.getId()) + 1;
            mapper.deleteById(child.getId());
        }
        return removed;
    }

    // ------------------------------------------------------------------ 下载

    public record Download(Path path, String name, String mime, long size) {}

    /** 校验文件还在磁盘上，并把下载次数加一。 */
    @Transactional
    public Download prepareDownload(Long id) {
        ToolNode node = require(id);
        if (!ToolNode.FILE.equals(node.getKind())) throw BizException.invalid("这是一个文件夹，不能下载");
        Path root = Paths.get(properties.upload().dir()).toAbsolutePath().normalize();
        Path path = root.resolve(node.getUrl().replaceFirst("^/uploads/", "")).normalize();
        if (!path.startsWith(root) || !Files.isReadable(path)) {
            throw BizException.notFound("文件");
        }
        mapper.update(null, Wrappers.<ToolNode>lambdaUpdate()
                .eq(ToolNode::getId, id)
                .setSql("download_count = download_count + 1"));
        return new Download(path, node.getName(), node.getMime(), node.getSize() == null ? 0 : node.getSize());
    }

    // ------------------------------------------------------------------ 小工具

    private ToolNode require(Long id) {
        ToolNode node = mapper.selectById(id);
        if (node == null) throw BizException.notFound("条目");
        return node;
    }

    private ToolNode requireFolder(Long id) {
        ToolNode node = require(id);
        if (!ToolNode.FOLDER.equals(node.getKind())) throw BizException.invalid("目标不是文件夹");
        return node;
    }

    private int depthOf(long parentId) {
        int depth = 0;
        long cursor = parentId;
        while (cursor != ROOT && depth < MAX_DEPTH + 2) {
            ToolNode node = mapper.selectById(cursor);
            if (node == null) break;
            cursor = node.getParentId() == null ? ROOT : node.getParentId();
            depth++;
        }
        return depth;
    }

    /** 同一层不能重名，否则「下载那个报告.pdf」就说不清是哪个了。 */
    private void requireUniqueName(Long parentId, String name, Long exceptId) {
        Long count = mapper.selectCount(Wrappers.<ToolNode>lambdaQuery()
                .eq(ToolNode::getParentId, parentId)
                .eq(ToolNode::getName, name)
                .ne(exceptId != null, ToolNode::getId, exceptId));
        if (count != null && count > 0) throw BizException.invalid("这一层已经有叫「" + name + "」的了");
    }

    /** 重名就自动加 (2)(3)，用户不必为了重名再改一次文件名。 */
    private String uniqueFileName(Long parentId, String name) {
        String base = name;
        String suffix = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            suffix = name.substring(dot);
        }
        String candidate = name;
        int index = 2;
        while (exists(parentId, candidate)) {
            candidate = base + "(" + index++ + ")" + suffix;
        }
        return candidate;
    }

    private boolean exists(Long parentId, String name) {
        Long count = mapper.selectCount(Wrappers.<ToolNode>lambdaQuery()
                .eq(ToolNode::getParentId, parentId)
                .eq(ToolNode::getName, name));
        return count != null && count > 0;
    }
}
