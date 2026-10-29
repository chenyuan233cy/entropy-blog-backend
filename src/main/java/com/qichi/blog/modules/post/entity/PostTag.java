package com.qichi.blog.modules.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("post_tag")
public class PostTag {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long postId;
    private Long tagId;
    private LocalDateTime createTime;
}
