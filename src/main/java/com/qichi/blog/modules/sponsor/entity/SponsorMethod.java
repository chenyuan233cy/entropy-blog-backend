package com.qichi.blog.modules.sponsor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条赞助方式：名称 + 类型 + 内容。
 *
 * <p>以前是一整段自由文本（sponsor.note），想放个赞赏码图片只能贴外链。拆成结构化之后，
 * 文本、链接、图片各有各的渲染方式，也不用站长自己拼 HTML。
 */
@Data
@TableName("sponsor_method")
public class SponsorMethod {

    public static final String TEXT = "TEXT";
    public static final String LINK = "LINK";
    public static final String IMAGE = "IMAGE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    /** TEXT / LINK / IMAGE */
    private String kind;
    /** 按 kind 解释：文本内容 / 链接地址 / 图片地址 */
    private String content;
    private Integer sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
