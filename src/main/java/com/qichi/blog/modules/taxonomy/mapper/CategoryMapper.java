package com.qichi.blog.modules.taxonomy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qichi.blog.modules.taxonomy.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    @Select("""
            SELECT c.id AS id, c.name AS name, c.slug AS slug, c.accent AS accent,
                   c.description AS description, c.sort_order AS sortOrder,
                   COUNT(p.id) AS postCount
            FROM category c
            LEFT JOIN post p ON p.category_id = c.id AND p.status = 'PUBLISHED' AND p.deleted = 0
            WHERE c.deleted = 0
            GROUP BY c.id, c.name, c.slug, c.accent, c.description, c.sort_order
            ORDER BY c.sort_order ASC, c.id ASC
            """)
    List<TaxonomyCountRow> selectWithPostCount();

    @Select("SELECT COUNT(*) FROM post WHERE category_id = #{categoryId} AND deleted = 0")
    long countPostsUsing(@Param("categoryId") Long categoryId);
}
