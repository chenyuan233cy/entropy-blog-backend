package com.qichi.blog.modules.taxonomy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qichi.blog.modules.taxonomy.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    @Select("""
            SELECT t.id AS id, t.name AS name, t.slug AS slug, t.accent AS accent,
                   COUNT(p.id) AS postCount
            FROM tag t
            LEFT JOIN post_tag pt ON pt.tag_id = t.id
            LEFT JOIN post p ON p.id = pt.post_id AND p.status = 'PUBLISHED' AND p.deleted = 0
            WHERE t.deleted = 0
            GROUP BY t.id, t.name, t.slug, t.accent
            ORDER BY postCount DESC, t.id ASC
            """)
    List<TaxonomyCountRow> selectWithPostCount();

    @Select("""
            SELECT COUNT(*) FROM post_tag pt
            JOIN post p ON p.id = pt.post_id
            WHERE pt.tag_id = #{tagId} AND p.deleted = 0
            """)
    long countPostsUsing(@Param("tagId") Long tagId);
}
