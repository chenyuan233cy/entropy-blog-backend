package com.qichi.blog.modules.post.mapper;

import lombok.Data;

/** Projection for the tag lookup that hydrates a page of posts in one query. */
@Data
public class PostTagRow {
    private Long postId;
    private Long tagId;
    private String name;
    private String slug;
    private String accent;
}
