package com.qichi.blog.modules.taxonomy.mapper;

import lombok.Data;

/** Flat projection used by the tag and category count queries. */
@Data
public class TaxonomyCountRow {
    private Long id;
    private String name;
    private String slug;
    private String accent;
    private String description;
    private Integer sortOrder;
    private Long postCount;
}
