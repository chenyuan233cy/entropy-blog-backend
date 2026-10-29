package com.qichi.blog.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.modules.user.entity.User;
import com.qichi.blog.modules.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 令牌版本号的唯一真相在 MySQL 的 {@code user.token_version}，Redis 只做 30 秒缓存。
 *
 * <p>以前它只在 Redis 里：Redis 一重启丢数据，版本号回到 0，所有「已登出」的访问令牌
 * 就重新有效了（fail-open）。现在丢缓存最多是多查一次库，撤销语义不会退化成放行。
 */
@Service
public class TokenVersionService {

    private static final String KEY_PREFIX = "blog:tokver:";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final UserMapper userMapper;
    private final CacheService cache;

    public TokenVersionService(UserMapper userMapper, CacheService cache) {
        this.userMapper = userMapper;
        this.cache = cache;
    }

    public long current(Long userId) {
        if (userId == null) return 0L;
        String key = KEY_PREFIX + userId;
        Long cached = cache.get(key, Long.class);
        if (cached != null) return cached;

        User user = userMapper.selectById(userId);
        long version = user == null || user.getTokenVersion() == null ? 0L : user.getTokenVersion();
        cache.put(key, version, TTL);
        return version;
    }

    /** 登出 / 改密码：版本号 +1，此前签出的所有令牌立刻作废。 */
    public void bump(Long userId) {
        if (userId == null) return;
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .setSql("token_version = token_version + 1"));
        cache.delete(KEY_PREFIX + userId);
    }
}
