package com.qichi.blog.modules.comment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("comment")
public class Comment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long postId;
    /** 发评论的账号；显示时用它当前的昵称头像，而不是下面那份快照 */
    private Long userId;
    private Long parentId;
    private Long rootId;
    private String replyToName;
    private String nickname;
    private String email;
    private String website;
    private String avatar;
    private String content;
    private String status;
    private Integer likeCount;
    private String ipHash;
    private String userAgent;
    private Integer adminReply;
    private Integer reportCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
