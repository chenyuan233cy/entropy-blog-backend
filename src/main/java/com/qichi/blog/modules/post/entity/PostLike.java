package com.qichi.blog.modules.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("post_like")
public class PostLike {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long postId;
    private String visitorId;
    private LocalDateTime createTime;
}
