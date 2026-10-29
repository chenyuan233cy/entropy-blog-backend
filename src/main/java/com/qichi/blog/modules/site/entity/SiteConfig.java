package com.qichi.blog.modules.site.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("site_config")
public class SiteConfig {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String configKey;
    private String configValue;
    private String valueType;
    private String groupName;
    private String description;
    private LocalDateTime updateTime;
}
