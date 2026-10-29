package com.qichi.blog.modules.comment.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.common.PageResult;
import com.qichi.blog.modules.comment.CommentDtos;
import com.qichi.blog.modules.comment.entity.Comment;
import com.qichi.blog.modules.comment.mapper.CommentMapper;
import com.qichi.blog.modules.post.entity.Post;
import com.qichi.blog.modules.post.mapper.PostMapper;
import com.qichi.blog.modules.site.service.SiteConfigService;
import com.qichi.blog.modules.user.entity.User;
import com.qichi.blog.modules.user.service.UserService;
import com.qichi.blog.security.HtmlSanitizer;
import com.qichi.blog.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CommentService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String SPAM = "SPAM";

    private static final List<String> AVATAR_KEYS = List.of("sage", "clay", "dust", "haze", "sand");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CommentService.class);

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final SiteConfigService configService;
    private final UserService userService;
    private final CacheService cache;
    private final HtmlSanitizer sanitizer;

    public CommentService(CommentMapper commentMapper, PostMapper postMapper,
                          SiteConfigService configService, UserService userService,
                          CacheService cache, HtmlSanitizer sanitizer) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.configService = configService;
        this.userService = userService;
        this.cache = cache;
        this.sanitizer = sanitizer;
    }

    // ------------------------------------------------------------------ public

    public PageResult<CommentDtos.CommentVO> listForPost(Long postId, long page, long size) {
        return listForPost(postId, page, size, null);
    }

    /** viewerId 是当前登录的人，用来标出「哪几条是我写的」（游客传 null）。 */
    public PageResult<CommentDtos.CommentVO> listForPost(Long postId, long page, long size, Long viewerId) {
        IPage<Comment> roots = commentMapper.selectPage(new Page<>(page, size),
                Wrappers.<Comment>lambdaQuery()
                        .eq(Comment::getPostId, postId)
                        .eq(Comment::getStatus, APPROVED)
                        .eq(Comment::getParentId, 0L)
                        .orderByDesc(Comment::getCreateTime));

        List<Comment> records = roots.getRecords();
        Map<Long, List<Comment>> replies = new LinkedHashMap<>();
        if (!records.isEmpty()) {
            List<Long> rootIds = records.stream().map(Comment::getId).toList();
            for (Comment reply : commentMapper.selectReplies(rootIds)) {
                replies.computeIfAbsent(reply.getRootId(), key -> new ArrayList<>()).add(reply);
            }
        }

        // 一次把所有涉及的账号捞出来，显示时用「现在」的昵称和头像，而不是当年那份快照
        List<Comment> every = new ArrayList<>(records);
        replies.values().forEach(every::addAll);
        Map<Long, User> accounts = accountsOf(every);

        List<CommentDtos.CommentVO> items = records.stream()
                .map(root -> toVO(root, replies.getOrDefault(root.getId(), List.of()).stream()
                        .map(reply -> toVO(reply, List.of(), accounts, viewerId)).toList(), accounts, viewerId))
                .toList();
        return PageResult.of(roots.getCurrent(), roots.getSize(), roots.getTotal(), items);
    }

    /** 评论里记了 user_id 的，把对应账号一次性查出来。没关联的（老数据、作者自己回复）就跳过。 */
    private Map<Long, User> accountsOf(List<Comment> comments) {
        List<Long> ids = comments.stream()
                .map(Comment::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, User> map = new LinkedHashMap<>();
        for (User user : userService.findAllByIds(ids)) map.put(user.getId(), user);
        return map;
    }

    /**
     * The caller must be signed in. Identity is read from the account rather than from the request
     * body, so a comment can never be posted under someone else's name.
     */
    @Transactional
    public CommentDtos.CommentVO create(CommentDtos.CommentCreateRequest request, LoginUser principal, HttpServletRequest http) {
        Post post = postMapper.selectById(request.postId());
        if (post == null || !post.published()) throw BizException.notFound("文章");
        if (post.getAllowComment() != null && post.getAllowComment() == 0) {
            throw new BizException(com.qichi.blog.common.ErrorCode.FORBIDDEN, "这篇文章关闭了评论");
        }

        User account = userService.requireById(principal.id());
        if (configService.requireBool("comment.require_email", true)
                && (account.getEmail() == null || account.getEmail().isBlank())) {
            throw BizException.invalid("请先在「我的资料」里填写邮箱");
        }

        // 命中屏蔽词就不收：告诉对方违规，不告诉他具体是哪个词，免得被绕过
        String blocked = firstBlockedWord(sanitizer.plain(request.content()));
        if (blocked != null) {
            throw BizException.invalid("这条评论里有不允许出现的词，发不出去。换个说法再试。");
        }

        long parentId = request.parentId() == null ? 0L : request.parentId();
        long rootId = 0L;
        String replyToName = null;
        if (parentId != 0L) {
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null || !parent.getPostId().equals(post.getId())) {
                throw BizException.invalid("被回复的评论不存在");
            }
            rootId = parent.getRootId() == null || parent.getRootId() == 0L ? parent.getId() : parent.getRootId();
            replyToName = parent.getNickname();
        }

        Comment comment = new Comment();
        comment.setPostId(post.getId());
        // 记下账号：这样以后换了头像昵称，评论里显示的也是最新的
        comment.setUserId(account.getId());
        comment.setParentId(parentId);
        comment.setRootId(rootId);
        comment.setReplyToName(replyToName);
        String nickname = account.getNickname() == null || account.getNickname().isBlank()
                ? account.getUsername() : account.getNickname();
        comment.setNickname(sanitizer.plain(nickname));
        comment.setEmail(account.getEmail() == null ? null : sanitizer.plain(account.getEmail()));
        comment.setWebsite(account.getWebsite() == null || account.getWebsite().isBlank()
                ? null : sanitizer.stripBrackets(account.getWebsite().trim()));
        comment.setContent(sanitizer.plain(request.content()));
        comment.setAvatar(account.getAvatar() == null || account.getAvatar().isBlank()
                ? "/avatars/" + AVATAR_KEYS.get(Math.abs(nickname.hashCode()) % AVATAR_KEYS.size()) + ".webp"
                : sanitizer.stripBrackets(account.getAvatar().trim()));
        comment.setLikeCount(0);
        // only the site author wears the 作者 badge; the studio's own replies set this too
        comment.setAdminReply(principal.isAdmin() ? 1 : 0);
        // 屏蔽词直接拒收，不进任何队列：没人需要一条一条审。剩下的一律直接发布，
        // 真出问题靠读者举报，站长再动手。
        comment.setStatus(APPROVED);
        comment.setIpHash(hash(clientIp(http)));
        comment.setUserAgent(truncate(http.getHeader("User-Agent"), 300));
        // set explicitly so the response carries a real timestamp instead of null
        comment.setCreateTime(LocalDateTime.now());
        commentMapper.insert(comment);

        if (APPROVED.equals(comment.getStatus())) {
            refreshPostCommentCount(post.getId());
        }
        // 自己刚发的那条当然是自己的 —— mine 要一起给出去，否则前端刚发完拿不到删除按钮
        return toVO(comment, List.of(), Map.of(account.getId(), account), account.getId());
    }

    /** 读者举报：只累加计数，是否违规由站长自己判断。 */
    @Transactional
    public void report(Long id, LoginUser principal) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null || !APPROVED.equals(comment.getStatus())) throw BizException.notFound("评论");
        // 举报自己是没意义的操作（界面也不给这个按钮），顺手在这里挡掉
        if (principal != null && comment.getUserId() != null && comment.getUserId().equals(principal.id())) {
            throw BizException.invalid("不能举报自己的评论");
        }
        commentMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                .eq(Comment::getId, id)
                .setSql("report_count = report_count + 1"));
    }

    /** 删自己的评论。站长也能删（走的是后台那套）。 */
    @Transactional
    public void deleteOwn(Long id, LoginUser principal) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null) throw BizException.notFound("评论");
        boolean mine = comment.getUserId() != null && comment.getUserId().equals(principal.id());
        if (!mine && !principal.isAdmin()) {
            throw new BizException(com.qichi.blog.common.ErrorCode.FORBIDDEN, "只能删自己的评论");
        }
        commentMapper.deleteById(id);
        // 回复也要跟着走，否则留下看不见的孤儿
        for (Comment reply : commentMapper.selectList(Wrappers.<Comment>lambdaQuery().eq(Comment::getRootId, id))) {
            commentMapper.deleteById(reply.getId());
        }
        refreshPostCommentCount(comment.getPostId());
    }

    /**
     * 命中屏蔽词返回那个词，否则 null。
     *
     * <p>先去掉空白和标点再比对，否则「操 你 妈」「操.你.妈」这种拆开写的就绕过去了。
     */
    private String firstBlockedWord(String content) {
        String raw = configService.requireString("comment.blocked_words", "");
        if (raw == null || raw.isBlank() || content == null) return null;

        String flat = content.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}·・]+", "");
        for (String piece : raw.split("[,，、\\s]+")) {
            String word = piece.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", "");
            if (!word.isEmpty() && flat.contains(word)) return piece.trim();
        }
        return null;
    }

    /** Sidebar widget. Returns at most a handful, newest first. */
    public List<CommentDtos.LatestCommentVO> latest(int limit) {
        List<Comment> comments = commentMapper.selectLatest(Math.min(Math.max(limit, 1), 12));
        if (comments.isEmpty()) return List.of();

        Map<Long, Post> posts = new LinkedHashMap<>();
        for (Post post : postMapper.selectByIds(comments.stream().map(Comment::getPostId).distinct().toList())) {
            posts.put(post.getId(), post);
        }

        return comments.stream().map(comment -> {
            Post post = posts.get(comment.getPostId());
            String content = comment.getContent();
            return new CommentDtos.LatestCommentVO(
                    comment.getId(), comment.getNickname(), comment.getAvatar(),
                    content.length() <= 60 ? content : content.substring(0, 60) + "…",
                    post == null ? null : post.getSlug(),
                    post == null ? null : post.getTitle(),
                    comment.getCreateTime());
        }).toList();
    }

    // ------------------------------------------------------------------ admin

    public PageResult<CommentDtos.AdminCommentVO> adminList(long page, long size, String status, String keyword, boolean reported) {
        var wrapper = Wrappers.<Comment>lambdaQuery();
        if (reported) {
            // 被举报的优先看：先按举报数，再按新到旧
            wrapper.gt(Comment::getReportCount, 0).orderByDesc(Comment::getReportCount);
        } else if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            wrapper.eq(Comment::getStatus, status.toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            String needle = sanitizer.stripBrackets(keyword).trim();
            wrapper.and(w -> w.like(Comment::getContent, needle).or().like(Comment::getNickname, needle));
        }
        wrapper.orderByDesc(Comment::getCreateTime);

        IPage<Comment> result = commentMapper.selectPage(new Page<>(page, size), wrapper);
        List<Comment> records = result.getRecords();

        Map<Long, String> postTitles = new LinkedHashMap<>();
        if (!records.isEmpty()) {
            List<Long> postIds = records.stream().map(Comment::getPostId).distinct().toList();
            for (Post post : postMapper.selectByIds(postIds)) {
                postTitles.put(post.getId(), post.getTitle());
            }
        }

        List<CommentDtos.AdminCommentVO> items = records.stream()
                .map(comment -> new CommentDtos.AdminCommentVO(
                        comment.getId(), comment.getPostId(), postTitles.get(comment.getPostId()),
                        comment.getParentId(), comment.getReplyToName(), comment.getNickname(),
                        comment.getEmail(), comment.getWebsite(), comment.getContent(), comment.getStatus(),
                        comment.getAdminReply() != null && comment.getAdminReply() == 1,
                        comment.getCreateTime(),
                        comment.getReportCount() == null ? 0 : comment.getReportCount()))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), items);
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        String normalized = status == null ? "" : status.toUpperCase();
        if (!List.of(APPROVED, REJECTED, SPAM, PENDING).contains(normalized)) {
            throw BizException.invalid("状态只能是 PENDING / APPROVED / REJECTED / SPAM");
        }
        Comment comment = commentMapper.selectById(id);
        if (comment == null) throw BizException.notFound("评论");
        Comment patch = new Comment();
        patch.setId(id);
        patch.setStatus(normalized);
        commentMapper.updateById(patch);
        refreshPostCommentCount(comment.getPostId());
    }

    @Transactional
    public CommentDtos.CommentVO adminReply(Long id, CommentDtos.CommentReplyRequest request) {
        Comment parent = commentMapper.selectById(id);
        if (parent == null) throw BizException.notFound("评论");
        long rootId = parent.getRootId() == null || parent.getRootId() == 0L ? parent.getId() : parent.getRootId();

        String words = sanitizer.plain(request.content());
        if (firstBlockedWord(words) != null) throw BizException.invalid("这条回复里有不允许出现的词，发不出去。");

        Comment reply = new Comment();
        reply.setPostId(parent.getPostId());
        reply.setParentId(parent.getId());
        reply.setRootId(rootId);
        reply.setReplyToName(parent.getNickname());
        reply.setNickname("作者");
        reply.setAvatar("/avatar.webp");
        reply.setContent(words);
        reply.setStatus(APPROVED);
        reply.setLikeCount(0);
        reply.setAdminReply(1);
        reply.setCreateTime(LocalDateTime.now());
        commentMapper.insert(reply);
        refreshPostCommentCount(parent.getPostId());
        return toVO(reply, List.of());
    }

    /**
     * 文章删掉时连带清掉它的评论。原来只删文章不删评论，
     * 评论就成了没爹的孤儿：文章列表里 0 篇，统计里却还挂着 5 条评论。
     */
    @Transactional
    public int deleteByPost(Long postId) {
        int removed = commentMapper.delete(Wrappers.<Comment>lambdaQuery().eq(Comment::getPostId, postId));
        cache.bumpVersion("comment");
        return removed;
    }

    @Transactional
    public void delete(Long id) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null) throw BizException.notFound("评论");
        commentMapper.deleteById(id);
        List<Comment> replies = commentMapper.selectList(
                Wrappers.<Comment>lambdaQuery().eq(Comment::getRootId, id));
        for (Comment reply : replies) {
            commentMapper.deleteById(reply.getId());
        }
        refreshPostCommentCount(comment.getPostId());
    }

    // ------------------------------------------------------------------ internals

    private void refreshPostCommentCount(Long postId) {
        postMapper.recountComments(postId);
        cache.bumpVersion("post");
    }

    private CommentDtos.CommentVO toVO(Comment comment, List<CommentDtos.CommentVO> replies) {
        return toVO(comment, replies, Map.of(), null);
    }

    /**
     * 行里存的是发表那一刻的身份快照，但显示要用账号**现在**的昵称和头像 ——
     * 不然换过头像以后，旧评论永远停在老头像上。没关联账号的（作者身份回复、老数据）就用快照。
     */
    private CommentDtos.CommentVO toVO(Comment comment, List<CommentDtos.CommentVO> replies,
                                       Map<Long, User> accounts, Long viewerId) {
        User account = comment.getUserId() == null ? null : accounts.get(comment.getUserId());
        boolean mine = viewerId != null && comment.getUserId() != null && comment.getUserId().equals(viewerId);
        String nickname = account != null ? account.getNickname() : comment.getNickname();
        String avatar = account != null ? account.getAvatar() : comment.getAvatar();
        String website = account != null ? account.getWebsite() : comment.getWebsite();
        // email and ipHash never leave the server
        return new CommentDtos.CommentVO(
                comment.getId(), comment.getParentId(), comment.getReplyToName(), nickname,
                avatar, website, comment.getContent(),
                comment.getLikeCount() == null ? 0 : comment.getLikeCount(),
                comment.getAdminReply() != null && comment.getAdminReply() == 1,
                comment.getCreateTime(),
                comment.getStatus(),
                comment.getReportCount() == null ? 0 : comment.getReportCount(),
                mine,
                replies);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
