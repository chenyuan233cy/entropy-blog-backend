package com.qichi.blog.modules.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qichi.blog.modules.post.entity.PostTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PostTagMapper extends BaseMapper<PostTag> {

    @Delete("DELETE FROM post_tag WHERE post_id = #{postId}")
    int deleteByPostId(@Param("postId") Long postId);
}
