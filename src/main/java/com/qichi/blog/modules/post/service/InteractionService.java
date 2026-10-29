package com.qichi.blog.modules.post.service;

import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.modules.post.PostDtos;
import com.qichi.blog.modules.post.entity.PostLike;
import com.qichi.blog.modules.post.mapper.PostLikeMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * View and like counters.
 *
 * <p>Both live in Redis and are written back to MySQL by {@code CounterFlushTask}. Reads add the
 * pending delta to the stored column, so the number a visitor sees is current even though the row
 * is only updated every few minutes.
 */
@Service
public class InteractionService {

    public static final String VIEW_PREFIX = "blog:post:view:";
    public static final String VIEW_SEEN_PREFIX = "blog:post:view:seen:";
    public static final String LIKE_PREFIX = "blog:post:like:";
    public static final String LIKE_SEEN_PREFIX = "blog:post:like:seen:";
    public static final String DIRTY_KEY = "blog:counter:dirty";

    private static final Duration VIEW_DEDUPE = Duration.ofMinutes(10);
    /**
     * 点赞去重的键必须有过期时间。以前这里是 null（永不过期）—— 访客标识是请求头里带的，
     * 换一个就是一个新键，等于给任何人一个免费往 Redis 里灌键的写入口。
     */
    private static final Duration LIKE_DEDUPE = Duration.ofDays(90);
    private static final java.util.regex.Pattern VISITOR_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final CacheService cache;
    private final PostLikeMapper postLikeMapper;

    public InteractionService(CacheService cache, PostLikeMapper postLikeMapper) {
        this.cache = cache;
        this.postLikeMapper = postLikeMapper;
    }

    /** Counts a view at most once per visitor per ten minutes. */
    public void recordView(Long postId, String visitorId) {
        if (postId == null || visitorId == null || visitorId.isBlank()) return;
        boolean first = cache.setIfAbsent(VIEW_SEEN_PREFIX + postId + ":" + visitorId, "1", VIEW_DEDUPE);
        if (!first) return;
        cache.increment(VIEW_PREFIX + postId, 1L);
        cache.addToSet(DIRTY_KEY, "view:" + postId);
    }

    public PostDtos.LikeResult like(Long postId, String visitorId) {
        if (visitorId == null || visitorId.isBlank()) {
            throw new BizException(com.qichi.blog.common.ErrorCode.PARAM_INVALID, "缺少访客标识 X-Visitor-Id");
        }
        String visitor = visitorId.trim();
        if (!VISITOR_ID.matcher(visitor).matches()) {
            throw new BizException(com.qichi.blog.common.ErrorCode.PARAM_INVALID, "访客标识不合法");
        }
        long stored = storedLikes(postId);
        boolean first = cache.setIfAbsent(LIKE_SEEN_PREFIX + postId + ":" + visitor, "1", LIKE_DEDUPE);
        if (!first) {
            return new PostDtos.LikeResult(false, stored + pendingLikes(postId));
        }
        try {
            PostLike like = new PostLike();
            like.setPostId(postId);
            like.setVisitorId(visitor);
            postLikeMapper.insert(like);
        } catch (DuplicateKeyException ignored) {
            // the unique index is the real guard; Redis only makes the common path cheap
        }
        cache.increment(LIKE_PREFIX + postId, 1L);
        cache.addToSet(DIRTY_KEY, "like:" + postId);
        return new PostDtos.LikeResult(true, stored + pendingLikes(postId));
    }

    public long pendingViews(Long postId) {
        return cache.getCounter(VIEW_PREFIX + postId);
    }

    public long pendingLikes(Long postId) {
        return cache.getCounter(LIKE_PREFIX + postId);
    }

    private long storedLikes(Long postId) {
        return 0L;
    }
}
