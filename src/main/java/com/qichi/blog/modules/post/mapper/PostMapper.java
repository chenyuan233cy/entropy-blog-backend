package com.qichi.blog.modules.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.qichi.blog.modules.post.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Note: MyBatis-Plus only applies its logical-delete filter to SQL it generates itself, so every
 * hand written statement here spells out {@code deleted = 0}. The paginated methods take an
 * {@link IPage} first argument, which is what lets the pagination interceptor rewrite them.
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    @Select("""
            <script>
            SELECT p.* FROM post p
            WHERE p.deleted = 0 AND p.status = 'PUBLISHED'
            <if test="tagId != null">
              AND EXISTS (SELECT 1 FROM post_tag pt WHERE pt.post_id = p.id AND pt.tag_id = #{tagId})
            </if>
            <if test="categoryId != null"> AND p.category_id = #{categoryId} </if>
            <if test="year != null"> AND YEAR(p.published_at) = #{year} </if>
            ORDER BY p.is_top DESC,
            <choose>
              <when test="sort == 'popular'"> p.view_count DESC </when>
              <otherwise> p.published_at DESC </otherwise>
            </choose>
            </script>
            """)
    IPage<Post> selectPublished(IPage<Post> page,
                                @Param("tagId") Long tagId,
                                @Param("categoryId") Long categoryId,
                                @Param("year") Integer year,
                                @Param("sort") String sort);

    /**
     * 搜索的是文章本身：标题、摘要、正文。以前只搜标题和标签名 —— 搜「Redis 缓存」这种正文里的词
     * 一篇都出不来，那就不叫搜索了。标题命中的排前面，其余按时间。
     */
    @Select("""
            SELECT p.* FROM post p
            WHERE p.deleted = 0 AND p.status = 'PUBLISHED'
              AND (p.title LIKE CONCAT('%', #{keyword}, '%')
                OR p.summary LIKE CONCAT('%', #{keyword}, '%')
                OR p.content_md LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY (p.title LIKE CONCAT('%', #{keyword}, '%')) DESC,
                     p.is_top DESC, p.published_at DESC
            """)
    IPage<Post> searchByKeyword(IPage<Post> page, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT * FROM post
            WHERE deleted = 0
            <if test="status != null and status != ''"> AND status = #{status} </if>
            <if test="keyword != null and keyword != ''"> AND title LIKE CONCAT('%', #{keyword}, '%') </if>
            ORDER BY update_time DESC
            </script>
            """)
    IPage<Post> selectAdmin(IPage<Post> page,
                            @Param("status") String status,
                            @Param("keyword") String keyword);

    @Select("""
            SELECT id, title, slug, summary, published_at AS publishedAt
            FROM post
            WHERE deleted = 0 AND status = 'PUBLISHED' AND published_at IS NOT NULL
            ORDER BY published_at DESC
            """)
    List<ArchiveRow> selectArchiveRows();

    @Select("""
            <script>
            SELECT * FROM post
            WHERE deleted = 0 AND status = 'PUBLISHED'
            <if test="publishedAt != null"> AND published_at &lt; #{publishedAt} </if>
            ORDER BY published_at DESC LIMIT 1
            </script>
            """)
    Post selectPrevious(@Param("publishedAt") LocalDateTime publishedAt);

    @Select("""
            <script>
            SELECT * FROM post
            WHERE deleted = 0 AND status = 'PUBLISHED'
            <if test="publishedAt != null"> AND published_at &gt; #{publishedAt} </if>
            ORDER BY published_at ASC LIMIT 1
            </script>
            """)
    Post selectNext(@Param("publishedAt") LocalDateTime publishedAt);

    @Select("""
            <script>
            SELECT * FROM post
            WHERE deleted = 0 AND status = 'PUBLISHED' AND id != #{excludeId}
            <if test="categoryId != null"> AND category_id = #{categoryId} </if>
            ORDER BY published_at DESC LIMIT #{limit}
            </script>
            """)
    List<Post> selectRelated(@Param("categoryId") Long categoryId,
                             @Param("excludeId") Long excludeId,
                             @Param("limit") int limit);

    @Select("""
            <script>
            SELECT pt.post_id AS postId, t.id AS tagId, t.name AS name, t.slug AS slug, t.accent AS accent
            FROM post_tag pt JOIN tag t ON t.id = pt.tag_id
            WHERE t.deleted = 0 AND pt.post_id IN
            <foreach collection="postIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </script>
            """)
    List<PostTagRow> selectTagsForPosts(@Param("postIds") List<Long> postIds);

    @Select("SELECT COUNT(*) FROM post WHERE deleted = 0 AND status = 'PUBLISHED'")
    long countPublished();

    @Select("SELECT COUNT(*) FROM post WHERE deleted = 0 AND status = 'DRAFT'")
    long countDrafts();

    @Select("SELECT COUNT(*) FROM post WHERE deleted = 0")
    long countAll();

    @Select("SELECT COALESCE(SUM(word_count), 0) FROM post WHERE deleted = 0 AND status = 'PUBLISHED'")
    long sumPublishedWords();

    @Update("UPDATE post SET view_count = view_count + #{delta} WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id, @Param("delta") long delta);

    @Update("UPDATE post SET like_count = like_count + #{delta} WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id, @Param("delta") long delta);

    @Update("""
            UPDATE post SET comment_count =
              (SELECT COUNT(*) FROM comment c WHERE c.post_id = #{id} AND c.deleted = 0 AND c.status = 'APPROVED')
            WHERE id = #{id}
            """)
    int recountComments(@Param("id") Long id);
}
