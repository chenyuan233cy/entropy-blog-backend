package com.qichi.blog.modules.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qichi.blog.modules.comment.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    @Select("""
            <script>
            SELECT * FROM comment
            WHERE deleted = 0 AND status = 'APPROVED' AND root_id IN
            <foreach collection="rootIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            ORDER BY create_time ASC
            </script>
            """)
    List<Comment> selectReplies(@Param("rootIds") List<Long> rootIds);

    /**
     * 统计评论数。必须连文章一起看：文章删掉之后，它的评论不该还留在计数里
     * （统计写在外面，才会出现「0 篇文章 / 5 条评论」这种自相矛盾的数字）。
     */
    @Select("""
            SELECT COUNT(*) FROM comment c
            JOIN post p ON p.id = c.post_id AND p.deleted = 0 AND p.status = 'PUBLISHED'
            WHERE c.deleted = 0 AND c.status = #{status}
            """)
    long countByStatus(@Param("status") String status);

    /** Sidebar widget: the most recent approved reader comments, admin replies excluded. */
    @Select("""
            SELECT c.* FROM comment c
            JOIN post p ON p.id = c.post_id AND p.deleted = 0 AND p.status = 'PUBLISHED'
            WHERE c.deleted = 0 AND c.status = 'APPROVED' AND c.admin_reply = 0
            ORDER BY c.create_time DESC
            LIMIT #{limit}
            """)
    List<Comment> selectLatest(@Param("limit") int limit);
}
