package com.qichi.blog.modules.post.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ArchiveRow {
    private Long id;
    private String title;
    private String slug;
    private String summary;
    private LocalDateTime publishedAt;
}
