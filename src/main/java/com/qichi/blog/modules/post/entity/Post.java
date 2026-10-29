package com.qichi.blog.modules.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("post")
public class Post {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String title;
    private String slug;
    private String summary;
    private String coverUrl;
    private String contentMd;
    private String contentHtml;
    private String tocJson;
    private String status;
    private Long categoryId;
    private Long authorId;
    private Long viewCount;
    private Long likeCount;
    private Integer commentCount;
    private Integer wordCount;
    private Integer readingMinutes;
    private Integer isTop;
    private Integer allowComment;
    private String seoTitle;
    private String seoDescription;
    private LocalDateTime publishedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    public boolean published() {
        return "PUBLISHED".equals(status);
    }
}
