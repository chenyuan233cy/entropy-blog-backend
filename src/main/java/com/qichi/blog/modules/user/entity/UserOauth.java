package com.qichi.blog.modules.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条第三方账号绑定。
 *
 * <p>放在独立表里而不是 user 行上的两个字段：那样一个账号只能绑一个平台，
 * 而且「解绑」等于把那两个字段清空 —— 想把 GitHub 换成 Google 就得先解绑再绑，
 * 中间那一下账号是没有第三方登录的。
 */
@Data
@TableName("user_oauth")
public class UserOauth {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    /** github / google */
    private String provider;
    /** 平台侧永不改变的用户 id（GitHub 的数字 id、Google 的 sub） */
    private String openId;
    /** 绑定时该平台上的邮箱，只作展示与排查用，不参与认人 */
    private String email;
    private String nickname;
    private LocalDateTime createTime;
}
