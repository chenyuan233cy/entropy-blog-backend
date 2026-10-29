package com.qichi.blog.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache facade over Redis with a process-local fallback.
 *
 * <p>Namespaces are versioned instead of being evicted by prefix. Writing an article bumps the
 * version of the {@code post} namespace, which makes every previously cached list key unreachable
 * without ever issuing KEYS or SCAN. Old keys simply expire.
 *
 * <p>If Redis is unreachable the service degrades to in-memory maps so the site keeps working.
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final String VERSION_KEY = "blog:v:";
    private static final long LOCAL_VERSION_TTL_MS = 5_000L;
    private static final long REDIS_RETRY_AFTER_MS = 30_000L;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    private final Map<String, LocalEntry> local = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> localCounters = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> localSets = new ConcurrentHashMap<>();
    private final Map<String, long[]> localVersions = new ConcurrentHashMap<>();

    private volatile long redisDownUntil = 0L;

    public CacheService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * Redis 现在能用吗。
     *
     * <p>登录态（刷新令牌白名单）不在这个降级范围里：缓存查不到只是「没有缓存」，
     * 但白名单查不到会被当成「令牌被吊销」——用户就被莫名其妙踢下线了。
     * 所以调用方要先问一句，Redis 不健康时宁可回一个「稍后再试」，也不要把人说成是未登录。
     */
    public boolean healthy() {
        return redisAvailable();
    }

    // ------------------------------------------------------------------ namespace

    /** Current version of a namespace, used when building cache keys. */
    public String ns(String name) {
        String key = VERSION_KEY + name;
        long now = System.currentTimeMillis();
        long[] cached = localVersions.get(key);
        if (cached != null && cached[1] > now) {
            return Long.toString(cached[0]);
        }
        String value = redisGet(key);
        if (value == null) {
            redisSetIfAbsent(key, "1", null);
            value = redisGet(key);
        }
        long version = parseLong(value, 1L);
        localVersions.put(key, new long[]{version, now + LOCAL_VERSION_TTL_MS});
        return Long.toString(version);
    }

    /** Invalidates a whole namespace by bumping its version. */
    public void bumpVersion(String name) {
        String key = VERSION_KEY + name;
        localVersions.remove(key);
        if (redisAvailable()) {
            try {
                redis.opsForValue().increment(key);
                return;
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        localCounters.computeIfAbsent(key, k -> new AtomicLong(1)).incrementAndGet();
    }

    public String postNs() { return ns("post"); }
    public String taxonomyNs() { return ns("taxonomy"); }
    public String commentNs() { return ns("comment"); }
    public String musicNs() { return ns("music"); }
    public String configNs() { return ns("config"); }

    // ----------------------------------------------------------------------- get/put

    public <T> T get(String key, Class<T> type) {
        String json = redisGet(key);
        if (json == null) {
            LocalEntry entry = local.get(key);
            if (entry == null) return null;
            if (entry.expiresAt < System.currentTimeMillis()) {
                local.remove(key);
                return null;
            }
            json = entry.json;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception ex) {
            log.warn("cache value for {} cannot be deserialized, dropping it", key);
            delete(key);
            return null;
        }
    }

    public void put(String key, Object value, Duration ttl) {
        if (value == null) return;
        String json;
        try {
            json = mapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("cannot serialize cache value for {}", key, ex);
            return;
        }
        if (redisAvailable()) {
            try {
                if (ttl == null) redis.opsForValue().set(key, json);
                else redis.opsForValue().set(key, json, ttl);
                return;
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        long ttlMs = ttl == null ? 3_600_000L : ttl.toMillis();
        local.put(key, new LocalEntry(json, System.currentTimeMillis() + ttlMs));
        pruneLocal();
    }

    // ----------------------------------------------------------------------- helpers

    public boolean delete(String key) {
        boolean deleted = false;
        if (redisAvailable()) {
            try {
                deleted = Boolean.TRUE.equals(redis.delete(key));
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        deleted |= local.remove(key) != null;
        return deleted;
    }

    public boolean setIfAbsent(String key, String value, Duration ttl) {
        if (redisAvailable()) {
            try {
                Boolean ok = ttl == null
                        ? redis.opsForValue().setIfAbsent(key, value)
                        : redis.opsForValue().setIfAbsent(key, value, ttl);
                return Boolean.TRUE.equals(ok);
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        long now = System.currentTimeMillis();
        LocalEntry existing = local.get(key);
        if (existing != null && existing.expiresAt > now) return false;
        long ttlMs = ttl == null ? 3_600_000L : ttl.toMillis();
        local.put(key, new LocalEntry(value, now + ttlMs));
        pruneLocal();
        return true;
    }

    public long increment(String key, long delta) {
        if (redisAvailable()) {
            try {
                Long value = redis.opsForValue().increment(key, delta);
                return value == null ? 0L : value;
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        return localCounters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
    }

    public long getCounter(String key) {
        if (redisAvailable()) {
            try {
                String value = redis.opsForValue().get(key);
                return parseLong(value, 0L);
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        AtomicLong counter = localCounters.get(key);
        return counter == null ? 0L : counter.get();
    }

    /** Reads and removes a counter, used when flushing counters into MySQL. */
    public long drainCounter(String key) {
        if (redisAvailable()) {
            try {
                String value = redis.opsForValue().getAndDelete(key);
                return parseLong(value, 0L);
            } catch (Exception ex) {
                if (ex instanceof org.springframework.dao.InvalidDataAccessApiUsageException
                        || (ex.getMessage() != null && ex.getMessage().contains("getdel"))) {
                    // Redis below 6.2 has no GETDEL; GET+DEL is close enough for a counter flush
                    try {
                        String value = redis.opsForValue().get(key);
                        if (value != null) redis.delete(key);
                        return parseLong(value, 0L);
                    } catch (Exception inner) {
                        log.debug("drainCounter fallback failed for {}", key);
                    }
                } else {
                    markRedisDown(ex);
                }
            }
        }
        AtomicLong counter = localCounters.remove(key);
        return counter == null ? 0L : counter.get();
    }

    public void addToSet(String key, String member) {
        if (redisAvailable()) {
            try {
                redis.opsForSet().add(key, member);
                return;
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        localSets.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(member);
    }

    public Set<String> members(String key) {
        if (redisAvailable()) {
            try {
                Set<String> members = redis.opsForSet().members(key);
                return members == null ? Set.of() : members;
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        Set<String> members = localSets.get(key);
        return members == null ? Set.of() : new LinkedHashSet<>(members);
    }

    public void removeFromSet(String key, String member) {
        if (redisAvailable()) {
            try {
                redis.opsForSet().remove(key, member);
                return;
            } catch (Exception ex) {
                markRedisDown(ex);
            }
        }
        Set<String> members = localSets.get(key);
        if (members != null) members.remove(member);
    }

    public boolean isRedisUp() {
        return redisAvailable();
    }

    // ----------------------------------------------------------------------- internals

    private String redisGet(String key) {
        if (!redisAvailable()) return null;
        try {
            return redis.opsForValue().get(key);
        } catch (Exception ex) {
            markRedisDown(ex);
            return null;
        }
    }

    private void redisSetIfAbsent(String key, String value, Duration ttl) {
        if (!redisAvailable()) return;
        try {
            if (ttl == null) redis.opsForValue().setIfAbsent(key, value);
            else redis.opsForValue().setIfAbsent(key, value, ttl);
        } catch (Exception ex) {
            markRedisDown(ex);
        }
    }

    private boolean redisAvailable() {
        return redis != null && System.currentTimeMillis() >= redisDownUntil;
    }

    private void markRedisDown(Exception ex) {
        if (System.currentTimeMillis() < redisDownUntil) return;
        redisDownUntil = System.currentTimeMillis() + REDIS_RETRY_AFTER_MS;
        log.warn("Redis unreachable ({}), falling back to the in-process cache for {}s",
                ex.getClass().getSimpleName(), REDIS_RETRY_AFTER_MS / 1000);
    }

    private void pruneLocal() {
        if (local.size() < 512) return;
        long now = System.currentTimeMillis();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, LocalEntry> entry : local.entrySet()) {
            if (entry.getValue().expiresAt < now) dead.add(entry.getKey());
        }
        dead.forEach(local::remove);
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record LocalEntry(String json, long expiresAt) {}
}
