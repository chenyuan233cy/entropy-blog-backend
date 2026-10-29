package com.qichi.blog.modules.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具页的一个节点，文件夹和文件共用一张表 —— 目录树只有一层父子关系，
 * 拆两张表反而要处处 union。
 */
@Data
@TableName("tool_node")
public class ToolNode {

    public static final String FOLDER = "FOLDER";
    public static final String FILE = "FILE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 0 表示在根目录 */
    private Long parentId;
    private String kind;
    private String name;
    private String url;
    private Long size;
    private String mime;
    private String ext;
    private String description;
    private Integer downloadCount;
    private Integer sortOrder;
    private Long ownerId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
