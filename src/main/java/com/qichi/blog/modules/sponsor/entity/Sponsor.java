package com.qichi.blog.modules.sponsor.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sponsor")
public class Sponsor {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    /** 金额。填 0 表示这位不想露数额，前台只显示名字 */
    private BigDecimal amount;

    // 这几个是「可以清空」的字段：MyBatis-Plus 默认更新时会跳过 null，
    // 于是把链接删掉再保存，数据库里那条旧链接纹丝不动。ALWAYS 才会真的写进 null。
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String message;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatarUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String link;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate sponsoredAt;
    /** 0 就先收起来，只是名单里留个记录 */
    private Integer visible;
    private Integer sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
