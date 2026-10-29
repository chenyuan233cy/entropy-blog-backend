package com.qichi.blog.modules.music.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("music")
public class Music {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String title;
    private String artist;
    private String album;
    private String coverUrl;
    private String audioUrl;
    private String lyricUrl;
    private String lyric;
    private Integer duration;
    private Long playlistId;
    private Integer sortOrder;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
