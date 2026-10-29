package com.qichi.blog.task;

import com.qichi.blog.cache.CacheService;
import com.qichi.blog.modules.post.mapper.PostMapper;
import com.qichi.blog.modules.post.service.InteractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Writes the Redis counters back into MySQL.
 *
 * <p>A dirty set records which posts changed, so the flush never has to enumerate keys, and each
 * counter is drained with an atomic get-and-delete so a value can never be counted twice.
 */
@Component
public class CounterFlushTask {

    private static final Logger log = LoggerFactory.getLogger(CounterFlushTask.class);

    private final CacheService cache;
    private final PostMapper postMapper;

    public CounterFlushTask(CacheService cache, PostMapper postMapper) {
        this.cache = cache;
        this.postMapper = postMapper;
    }

    @Scheduled(initialDelay = 60_000L, fixedDelay = 300_000L)
    public void flush() {
        Set<String> dirty = cache.members(InteractionService.DIRTY_KEY);
        if (dirty.isEmpty()) return;

        int flushed = 0;
        for (String entry : dirty) {
            int separator = entry.indexOf(':');
            if (separator < 0) {
                cache.removeFromSet(InteractionService.DIRTY_KEY, entry);
                continue;
            }
            String kind = entry.substring(0, separator);
            long postId;
            try {
                postId = Long.parseLong(entry.substring(separator + 1));
            } catch (NumberFormatException ex) {
                cache.removeFromSet(InteractionService.DIRTY_KEY, entry);
                continue;
            }

            if ("view".equals(kind)) {
                long delta = cache.drainCounter(InteractionService.VIEW_PREFIX + postId);
                if (delta > 0) postMapper.incrementViewCount(postId, delta);
            } else if ("like".equals(kind)) {
                long delta = cache.drainCounter(InteractionService.LIKE_PREFIX + postId);
                if (delta > 0) postMapper.incrementLikeCount(postId, delta);
            }
            cache.removeFromSet(InteractionService.DIRTY_KEY, entry);
            flushed++;
        }
        if (flushed > 0) log.debug("flushed counters for {} posts", flushed);
    }
}
