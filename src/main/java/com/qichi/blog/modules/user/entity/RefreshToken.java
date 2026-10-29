package com.qichi.blog.modules.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌白名单的一行。
 *
 * <p><b>为什么放在数据库而不是 Redis：</b>它决定「这个人还算不算登录着」。
 * 放在缓存里时，Redis 一抖（重启、连接断、30 秒熔断窗口），所有人都被当成未登录 ——
 * 但他们的令牌其实一直好好的。缓存可以丢，登录态不能丢。
 */
@Data
@TableName("refresh_token")
public class RefreshToken {

    /** 刷新令牌的 jti */
    @TableId
    private String jti;

    private Long userId;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
}
