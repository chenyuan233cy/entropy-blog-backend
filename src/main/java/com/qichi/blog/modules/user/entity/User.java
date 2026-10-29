package com.qichi.blog.modules.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;
    private String password;
    /** 0 = 密码是第三方建号时的随机占位，用户还没设过真密码 */
    private Integer passwordSet;
    private String nickname;
    private String email;
    private String avatar;
    private String bio;
    private String location;
    private String website;
    private String socialLinks;
    private String role;
    private Integer status;
    private String oauthProvider;
    private String oauthOpenId;
    /** 资源目录名，随机串 —— 不是自增 id */
    private String resourceKey;
    private LocalDateTime lastLoginAt;
    /** 令牌版本号：登出 / 改密码时 +1；签出的令牌必须带着这个号才算数（落库，不怕 Redis 丢数据） */
    private Long tokenVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
