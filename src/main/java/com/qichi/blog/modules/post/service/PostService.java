package com.qichi.blog.modules.post.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.common.PageResult;
import com.qichi.blog.common.SlugUtils;
import com.qichi.blog.modules.post.PostDtos;
import com.qichi.blog.modules.post.entity.Post;
import com.qichi.blog.modules.post.entity.PostTag;
import com.qichi.blog.modules.post.mapper.ArchiveRow;
import com.qichi.blog.modules.post.mapper.PostMapper;
import com.qichi.blog.modules.post.mapper.PostTagMapper;
import com.qichi.blog.modules.post.mapper.PostTagRow;
import com.qichi.blog.modules.taxonomy.TaxonomyDtos;
import com.qichi.blog.modules.taxonomy.entity.Category;
import com.qichi.blog.modules.taxonomy.entity.Tag;
import com.qichi.blog.modules.taxonomy.service.TaxonomyService;
import com.qichi.blog.security.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);
    private static final Duration DETAIL_TTL = Duration.ofMinutes(10);

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    private final PostMapper postMapper;
    private final PostTagMapper postTagMapper;
    private final TaxonomyService taxonomyService;
    private final MarkdownService markdownService;
    private final InteractionService interaction;
    private final CacheService cache;
    private final HtmlSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    /** 删文章要连带删评论，所以这里直接依赖评论服务（它只依赖 PostMapper，不会成环） */
    private final com.qichi.blog.modules.comment.service.CommentService commentService;

    public PostService(PostMapper postMapper, PostTagMapper postTagMapper, TaxonomyService taxonomyService,
                       MarkdownService markdownService, InteractionService interaction, CacheService cache,
                       HtmlSanitizer sanitizer, ObjectMapper objectMapper,
                       com.qichi.blog.modules.comment.service.CommentService commentService) {
        this.postMapper = postMapper;
        this.postTagMapper = postTagMapper;
        this.taxonomyService = taxonomyService;
        this.markdownService = markdownService;
        this.interaction = interaction;
        this.cache = cache;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.commentService = commentService;
    }

    // ------------------------------------------------------------------ public reads

    public PageResult<PostDtos.PostSummaryVO> list(long page, long size, String tagSlug, String categorySlug,
                                                   Integer year, String sort) {
        Long tagId = null;
        if (tagSlug != null && !tagSlug.isBlank()) {
            Tag tag = taxonomyService.findTagBySlug(tagSlug);
            if (tag == null) return PageResult.empty(page, size);
            tagId = tag.getId();
        }
        Long categoryId = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            Category category = taxonomyService.findCategoryBySlug(categorySlug);
            if (category == null) return PageResult.empty(page, size);
            categoryId = category.getId();
        }

        // Cached as an array plus a separate total, never as a raw PageResult: a bare Class token
        // erases the element type and Jackson would hand back LinkedHashMaps instead of records.
        String cacheKey = "blog:post:list:" + cache.postNs() + ":" + page + ":" + size + ":" + tagSlug
                + ":" + categorySlug + ":" + year + ":" + sort;
        PostDtos.PostSummaryVO[] cachedItems = cache.get(cacheKey + ":items", PostDtos.PostSummaryVO[].class);
        Long cachedTotal = cache.get(cacheKey + ":total", Long.class);
        if (cachedItems != null && cachedTotal != null) {
            return PageResult.of(page, size, cachedTotal, List.of(cachedItems));
        }

        IPage<Post> result = postMapper.selectPublished(new Page<>(page, size), tagId, categoryId, year, sort);
        List<PostDtos.PostSummaryVO> items = assemble(result.getRecords());
        cache.put(cacheKey + ":items", items.toArray(new PostDtos.PostSummaryVO[0]), Duration.ofMinutes(2));
        cache.put(cacheKey + ":total", result.getTotal(), Duration.ofMinutes(2));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), items);
    }

    public List<PostDtos.PostSummaryVO> featured(int limit) {
        IPage<Post> result = postMapper.selectPublished(new Page<>(1, limit), null, null, null, "latest");
        return assemble(result.getRecords());
    }

    public PageResult<PostDtos.SearchResultVO> search(String keyword, long page, long size) {
        String needle = keyword == null ? "" : sanitizer.stripBrackets(keyword).trim();
        if (needle.isEmpty()) return PageResult.empty(page, size);

        IPage<Post> result = postMapper.searchByKeyword(new Page<>(page, size), needle);
        List<PostDtos.PostSummaryVO> summaries = assemble(result.getRecords());
        String lowered = needle.toLowerCase();
        List<PostDtos.SearchResultVO> items = summaries.stream()
                .map(summary -> new PostDtos.SearchResultVO(summary, summary.tags().stream()
                        .map(PostDtos.TagBrief::name)
                        .filter(name -> name.toLowerCase().contains(lowered))
                        .toList()))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), items);
    }

    public PostDtos.PostDetailVO detail(String slug, String visitorId) {
        String cacheKey = "blog:post:detail:" + cache.postNs() + ":" + slug;
        PostDtos.PostDetailVO cached = cache.get(cacheKey, PostDtos.PostDetailVO.class);

        Post post = requirePublishedBySlug(slug);
        interaction.recordView(post.getId(), visitorId);

        if (cached != null) return withLiveCounters(cached, post);

        ensureRendered(post);
        PostDtos.PostDetailVO detail = buildDetail(post);
        cache.put(cacheKey, detail, DETAIL_TTL);
        return withLiveCounters(detail, post);
    }

    public PostDtos.AdjacentVO adjacent(String slug) {
        Post post = requirePublishedBySlug(slug);
        Post previous = postMapper.selectPrevious(post.getPublishedAt());
        Post next = postMapper.selectNext(post.getPublishedAt());
        return new PostDtos.AdjacentVO(
                previous == null ? null : new PostDtos.AdjacentItem(previous.getTitle(), previous.getSlug()),
                next == null ? null : new PostDtos.AdjacentItem(next.getTitle(), next.getSlug()));
    }

    public List<PostDtos.ArchiveGroupVO> archives() {
        List<ArchiveRow> rows = postMapper.selectArchiveRows();
        Map<Integer, List<PostDtos.ArchiveItem>> grouped = new TreeMap<>(Comparator.reverseOrder());
        for (ArchiveRow row : rows) {
            if (row.getPublishedAt() == null) continue;
            grouped.computeIfAbsent(row.getPublishedAt().getYear(), year -> new ArrayList<>())
                    .add(new PostDtos.ArchiveItem(row.getId(), row.getTitle(), row.getSlug(),
                            row.getSummary(), row.getPublishedAt()));
        }
        List<PostDtos.ArchiveGroupVO> groups = new ArrayList<>();
        grouped.forEach((year, items) -> groups.add(new PostDtos.ArchiveGroupVO(year, items.size(), items)));
        return groups;
    }

    // ------------------------------------------------------------------ admin

    public PageResult<PostDtos.AdminPostVO> adminList(long page, long size, String status, String keyword) {
        IPage<Post> result = postMapper.selectAdmin(new Page<>(page, size), status, keyword);
        List<Post> records = result.getRecords();
        Map<Long, String> categoryNames = taxonomyService.listCategories().stream()
                .collect(Collectors.toMap(TaxonomyDtos.CategoryVO::id, TaxonomyDtos.CategoryVO::name, (a, b) -> a));
        Map<Long, List<String>> tagNames = records.isEmpty() ? Map.of() : tagNamesFor(records.stream().map(Post::getId).toList());

        List<PostDtos.AdminPostVO> items = records.stream().map(post -> new PostDtos.AdminPostVO(
                post.getId(), post.getTitle(), post.getSlug(), post.getStatus(),
                post.getCategoryId() == null ? null : categoryNames.get(post.getCategoryId()),
                tagNames.getOrDefault(post.getId(), List.of()),
                post.getViewCount() == null ? 0 : post.getViewCount(),
                post.getCommentCount() == null ? 0 : post.getCommentCount(),
                post.getReadingMinutes() == null ? 1 : post.getReadingMinutes(),
                post.getIsTop() != null && post.getIsTop() == 1,
                post.getPublishedAt(), post.getUpdateTime())).toList();

        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), items);
    }

    public PostDtos.AdminPostDetailVO adminDetail(Long id) {
        Post post = postMapper.selectById(id);
        if (post == null) throw BizException.notFound("文章");
        List<Long> tagIds = postTagMapper.selectList(
                        Wrappers.<PostTag>lambdaQuery().eq(PostTag::getPostId, id)).stream()
                .map(PostTag::getTagId).toList();
        return new PostDtos.AdminPostDetailVO(
                post.getId(), post.getTitle(), post.getSlug(), post.getSummary(), post.getCoverUrl(),
                post.getContentMd(), post.getCategoryId(), tagIds, post.getStatus(),
                post.getIsTop() != null && post.getIsTop() == 1,
                post.getAllowComment() == null || post.getAllowComment() == 1,
                post.getSeoTitle(), post.getSeoDescription(), post.getPublishedAt(),
                post.getCreateTime(), post.getUpdateTime());
    }

    @Transactional
    public Long create(PostDtos.PostSaveRequest request) {
        Post post = new Post();
        applyRequest(post, request);
        post.setAuthorId(1000L);
        post.setViewCount(0L);
        post.setLikeCount(0L);
        post.setCommentCount(0);
        postMapper.insert(post);
        syncTags(post.getId(), request.tagIds());
        invalidate();
        return post.getId();
    }

    @Transactional
    public void update(Long id, PostDtos.PostSaveRequest request) {
        Post post = postMapper.selectById(id);
        if (post == null) throw BizException.notFound("文章");
        applyRequest(post, request);
        postMapper.updateById(post);
        syncTags(id, request.tagIds());
        invalidate();
    }

    @Transactional
    public void delete(Long id) {
        Post post = postMapper.selectById(id);
        if (post == null) throw BizException.notFound("文章");
        postTagMapper.deleteByPostId(id);
        postMapper.deleteById(id);
        // 评论也要一起收走，否则会留下看不见又算着数的孤儿评论
        commentService.deleteByPost(id);
        invalidate();
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        if (!STATUS_DRAFT.equals(status) && !STATUS_PUBLISHED.equals(status)) {
            throw BizException.invalid("状态只能是 DRAFT 或 PUBLISHED");
        }
        Post post = postMapper.selectById(id);
        if (post == null) throw BizException.notFound("文章");
        Post patch = new Post();
        patch.setId(id);
        patch.setStatus(status);
        if (STATUS_PUBLISHED.equals(status) && post.getPublishedAt() == null) {
            patch.setPublishedAt(LocalDateTime.now());
        }
        postMapper.updateById(patch);
        invalidate();
    }

    @Transactional
    public void toggleTop(Long id, boolean top) {
        Post patch = new Post();
        patch.setId(id);
        patch.setIsTop(top ? 1 : 0);
        postMapper.updateById(patch);
        invalidate();
    }

    public void recountComments(Long postId) {
        postMapper.recountComments(postId);
        invalidate();
    }

    // ------------------------------------------------------------------ internals

    public Post requirePublishedBySlug(String slug) {
        Post post = postMapper.selectOne(Wrappers.<Post>lambdaQuery()
                .eq(Post::getSlug, slug)
                .eq(Post::getStatus, STATUS_PUBLISHED));
        if (post == null) throw BizException.notFound("文章");
        return post;
    }

    /** Renders markdown on first read if the row predates the renderer, then stores the result. */
    public void ensureRendered(Post post) {
        if (post.getContentHtml() != null && !post.getContentHtml().isBlank()) return;
        MarkdownService.Rendered rendered = markdownService.render(post.getContentMd());
        Post patch = new Post();
        patch.setId(post.getId());
        patch.setContentHtml(rendered.html());
        patch.setTocJson(rendered.tocJson());
        patch.setWordCount(rendered.wordCount());
        patch.setReadingMinutes(rendered.readingMinutes());
        postMapper.updateById(patch);
        post.setContentHtml(rendered.html());
        post.setTocJson(rendered.tocJson());
        post.setWordCount(rendered.wordCount());
        post.setReadingMinutes(rendered.readingMinutes());
    }

    private void applyRequest(Post post, PostDtos.PostSaveRequest request) {
        String title = sanitizer.plain(request.title());
        String slug = request.slug() == null || request.slug().isBlank()
                ? SlugUtils.slugify(title, "post")
                : SlugUtils.slugify(request.slug(), "post");

        Post clash = postMapper.selectOne(Wrappers.<Post>lambdaQuery().eq(Post::getSlug, slug));
        if (clash != null && !clash.getId().equals(post.getId())) {
            throw BizException.conflict("slug 已被占用：" + slug);
        }

        MarkdownService.Rendered rendered = markdownService.render(request.contentMd());
        String status = STATUS_PUBLISHED.equals(request.status()) ? STATUS_PUBLISHED : STATUS_DRAFT;

        post.setTitle(title);
        post.setSlug(slug);
        post.setSummary(request.summary() == null || request.summary().isBlank()
                ? autoSummary(request.contentMd())
                : sanitizer.plain(request.summary()));
        post.setCoverUrl(request.coverUrl() == null ? null : sanitizer.stripBrackets(request.coverUrl().trim()));
        post.setContentMd(request.contentMd());
        post.setContentHtml(rendered.html());
        post.setTocJson(rendered.tocJson());
        post.setWordCount(rendered.wordCount());
        post.setReadingMinutes(rendered.readingMinutes());
        post.setStatus(status);
        post.setCategoryId(request.categoryId());
        post.setIsTop(Boolean.TRUE.equals(request.top()) ? 1 : 0);
        post.setAllowComment(Boolean.FALSE.equals(request.allowComment()) ? 0 : 1);
        post.setSeoTitle(request.seoTitle());
        post.setSeoDescription(request.seoDescription());
        post.setPublishedAt(request.publishedAt() != null ? request.publishedAt()
                : (STATUS_PUBLISHED.equals(status) ? LocalDateTime.now() : post.getPublishedAt()));
    }

    private String autoSummary(String markdown) {
        String plain = markdown
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[#>*`_~]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return plain.length() <= 180 ? plain : plain.substring(0, 180) + "…";
    }

    private void syncTags(Long postId, List<Long> tagIds) {
        postTagMapper.deleteByPostId(postId);
        if (tagIds == null || tagIds.isEmpty()) return;
        Set<Long> unique = new HashSet<>(tagIds);
        for (Long tagId : unique) {
            PostTag link = new PostTag();
            link.setPostId(postId);
            link.setTagId(tagId);
            postTagMapper.insert(link);
        }
    }

    public void invalidate() {
        cache.bumpVersion("post");
        cache.bumpVersion("taxonomy");
    }

    private Map<Long, List<String>> tagNamesFor(List<Long> postIds) {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (PostTagRow row : postMapper.selectTagsForPosts(postIds)) {
            out.computeIfAbsent(row.getPostId(), key -> new ArrayList<>()).add(row.getName());
        }
        return out;
    }

    public List<PostDtos.PostSummaryVO> assemble(List<Post> posts) {
        if (posts == null || posts.isEmpty()) return List.of();

        Map<Long, List<PostDtos.TagBrief>> tagsByPost = new LinkedHashMap<>();
        List<Long> ids = posts.stream().map(Post::getId).toList();
        for (PostTagRow row : postMapper.selectTagsForPosts(ids)) {
            tagsByPost.computeIfAbsent(row.getPostId(), key -> new ArrayList<>())
                    .add(new PostDtos.TagBrief(row.getTagId(), row.getName(), row.getSlug(), row.getAccent()));
        }
        Map<Long, TaxonomyDtos.CategoryVO> categories = taxonomyService.listCategories().stream()
                .collect(Collectors.toMap(TaxonomyDtos.CategoryVO::id, category -> category, (a, b) -> a));

        List<PostDtos.PostSummaryVO> out = new ArrayList<>(posts.size());
        for (Post post : posts) {
            TaxonomyDtos.CategoryVO category = post.getCategoryId() == null ? null : categories.get(post.getCategoryId());
            long views = stored(post.getViewCount()) + interaction.pendingViews(post.getId());
            long likes = stored(post.getLikeCount()) + interaction.pendingLikes(post.getId());
            out.add(new PostDtos.PostSummaryVO(
                    post.getId(), post.getTitle(), post.getSlug(), post.getSummary(), post.getCoverUrl(),
                    category == null ? null : new PostDtos.CategoryBrief(category.id(), category.name(), category.slug(), category.accent()),
                    tagsByPost.getOrDefault(post.getId(), List.of()),
                    views, likes,
                    post.getCommentCount() == null ? 0 : post.getCommentCount(),
                    post.getReadingMinutes() == null ? 1 : post.getReadingMinutes(),
                    post.getIsTop() != null && post.getIsTop() == 1,
                    post.getPublishedAt()));
        }
        return out;
    }

    private PostDtos.PostDetailVO buildDetail(Post post) {
        PostDtos.PostSummaryVO summary = assemble(List.of(post)).get(0);

        List<PostDtos.TocItem> toc = List.of();
        if (post.getTocJson() != null && !post.getTocJson().isBlank()) {
            try {
                toc = List.of(objectMapper.readValue(post.getTocJson(), PostDtos.TocItem[].class));
            } catch (Exception ex) {
                log.warn("toc for post {} is unreadable, serving an empty sidebar", post.getId());
            }
        }

        Post previous = postMapper.selectPrevious(post.getPublishedAt());
        Post next = postMapper.selectNext(post.getPublishedAt());
        PostDtos.AdjacentVO adjacent = new PostDtos.AdjacentVO(
                previous == null ? null : new PostDtos.AdjacentItem(previous.getTitle(), previous.getSlug()),
                next == null ? null : new PostDtos.AdjacentItem(next.getTitle(), next.getSlug()));

        List<Post> relatedPosts = postMapper.selectRelated(post.getCategoryId(), post.getId(), 3);
        if (relatedPosts.isEmpty()) {
            relatedPosts = postMapper.selectPublished(new Page<>(1, 3), null, null, null, "latest").getRecords().stream()
                    .filter(candidate -> !Objects.equals(candidate.getId(), post.getId()))
                    .toList();
        }

        return new PostDtos.PostDetailVO(
                post.getId(), post.getTitle(), post.getSlug(), post.getSummary(), post.getCoverUrl(),
                post.getContentHtml(), toc, summary.category(), summary.tags(),
                summary.viewCount(), summary.likeCount(), summary.commentCount(), summary.readingMinutes(),
                post.getWordCount() == null ? 0 : post.getWordCount(),
                summary.top(), post.getAllowComment() == null || post.getAllowComment() == 1,
                post.getPublishedAt(), post.getUpdateTime(), adjacent, assemble(relatedPosts),
                post.getSeoTitle(), post.getSeoDescription());
    }

    /** Keeps the cached payload while refreshing the counters that change on every request. */
    private PostDtos.PostDetailVO withLiveCounters(PostDtos.PostDetailVO detail, Post post) {
        long views = stored(post.getViewCount()) + interaction.pendingViews(post.getId());
        long likes = stored(post.getLikeCount()) + interaction.pendingLikes(post.getId());
        if (views == detail.viewCount() && likes == detail.likeCount()) return detail;
        return new PostDtos.PostDetailVO(
                detail.id(), detail.title(), detail.slug(), detail.summary(), detail.coverUrl(),
                detail.contentHtml(), detail.toc(), detail.category(), detail.tags(),
                views, likes, detail.commentCount(), detail.readingMinutes(), detail.wordCount(),
                detail.top(), detail.allowComment(), detail.publishedAt(), detail.updateTime(),
                detail.adjacent(), detail.related(), detail.seoTitle(), detail.seoDescription());
    }

    private long stored(Long value) {
        return value == null ? 0L : value;
    }
}
