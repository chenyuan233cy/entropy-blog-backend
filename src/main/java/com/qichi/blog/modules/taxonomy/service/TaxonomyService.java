package com.qichi.blog.modules.taxonomy.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.common.SlugUtils;
import com.qichi.blog.modules.taxonomy.TaxonomyDtos;
import com.qichi.blog.modules.taxonomy.entity.Category;
import com.qichi.blog.modules.taxonomy.entity.Tag;
import com.qichi.blog.modules.taxonomy.mapper.CategoryMapper;
import com.qichi.blog.modules.taxonomy.mapper.TagMapper;
import com.qichi.blog.modules.taxonomy.mapper.TaxonomyCountRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
public class TaxonomyService {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final TagMapper tagMapper;
    private final CategoryMapper categoryMapper;
    private final CacheService cache;

    public TaxonomyService(TagMapper tagMapper, CategoryMapper categoryMapper, CacheService cache) {
        this.tagMapper = tagMapper;
        this.categoryMapper = categoryMapper;
        this.cache = cache;
    }

    // ------------------------------------------------------------------ reads

    public List<TaxonomyDtos.TagVO> listTags() {
        String key = "blog:tag:list:" + cache.taxonomyNs();
        TaxonomyDtos.TagVO[] cached = cache.get(key, TaxonomyDtos.TagVO[].class);
        if (cached != null) return List.of(cached);

        List<TaxonomyDtos.TagVO> tags = tagMapper.selectWithPostCount().stream()
                .map(row -> new TaxonomyDtos.TagVO(row.getId(), row.getName(), row.getSlug(), row.getAccent(), row.getPostCount()))
                .toList();
        cache.put(key, tags, TTL);
        return tags;
    }

    public List<TaxonomyDtos.CategoryVO> listCategories() {
        String key = "blog:category:list:" + cache.taxonomyNs();
        TaxonomyDtos.CategoryVO[] cached = cache.get(key, TaxonomyDtos.CategoryVO[].class);
        if (cached != null) return List.of(cached);

        List<TaxonomyDtos.CategoryVO> categories = categoryMapper.selectWithPostCount().stream()
                .map(row -> new TaxonomyDtos.CategoryVO(row.getId(), row.getName(), row.getSlug(),
                        row.getDescription(), row.getAccent(), row.getSortOrder(), row.getPostCount()))
                .toList();
        cache.put(key, categories, TTL);
        return categories;
    }

    public List<TaxonomyCountRow> rawTagCounts() {
        return tagMapper.selectWithPostCount();
    }

    public Tag findTagBySlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        return tagMapper.selectOne(Wrappers.<Tag>lambdaQuery().eq(Tag::getSlug, slug));
    }

    public Category findCategoryBySlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        return categoryMapper.selectOne(Wrappers.<Category>lambdaQuery().eq(Category::getSlug, slug));
    }

    public List<Tag> findTagsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return tagMapper.selectByIds(ids);
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public TaxonomyDtos.TagVO createTag(TaxonomyDtos.TagSaveRequest request) {
        String slug = resolveSlug(request.slug(), request.name(), "tag");
        if (tagMapper.selectCount(Wrappers.<Tag>lambdaQuery().eq(Tag::getSlug, slug)) > 0) {
            throw BizException.conflict("标签 slug 已存在：" + slug);
        }
        if (tagMapper.selectCount(Wrappers.<Tag>lambdaQuery().eq(Tag::getName, request.name().trim())) > 0) {
            throw BizException.conflict("标签名称已存在：" + request.name());
        }
        Tag tag = new Tag();
        tag.setName(request.name().trim());
        tag.setSlug(slug);
        tag.setAccent(request.accent() == null || request.accent().isBlank() ? "sage" : request.accent());
        tagMapper.insert(tag);
        invalidate();
        return new TaxonomyDtos.TagVO(tag.getId(), tag.getName(), tag.getSlug(), tag.getAccent(), 0L);
    }

    @Transactional
    public TaxonomyDtos.TagVO updateTag(Long id, TaxonomyDtos.TagSaveRequest request) {
        Tag existing = tagMapper.selectById(id);
        if (existing == null) throw BizException.notFound("标签");
        existing.setName(request.name().trim());
        if (request.slug() != null && !request.slug().isBlank()) existing.setSlug(request.slug().trim());
        if (request.accent() != null && !request.accent().isBlank()) existing.setAccent(request.accent());
        tagMapper.updateById(existing);
        invalidate();
        return new TaxonomyDtos.TagVO(existing.getId(), existing.getName(), existing.getSlug(), existing.getAccent(), 0L);
    }

    @Transactional
    public void deleteTag(Long id) {
        Tag existing = tagMapper.selectById(id);
        if (existing == null) throw BizException.notFound("标签");
        long used = tagMapper.countPostsUsing(id);
        if (used > 0) throw BizException.conflict("该标签下还有 " + used + " 篇文章，无法删除");
        tagMapper.deleteById(id);
        invalidate();
    }

    @Transactional
    public TaxonomyDtos.CategoryVO createCategory(TaxonomyDtos.CategorySaveRequest request) {
        String slug = resolveSlug(request.slug(), request.name(), "category");
        if (categoryMapper.selectCount(Wrappers.<Category>lambdaQuery().eq(Category::getSlug, slug)) > 0) {
            throw BizException.conflict("分类 slug 已存在：" + slug);
        }
        Category category = new Category();
        category.setName(request.name().trim());
        category.setSlug(slug);
        category.setDescription(request.description());
        category.setAccent(request.accent() == null || request.accent().isBlank() ? "sage" : request.accent());
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        categoryMapper.insert(category);
        invalidate();
        return new TaxonomyDtos.CategoryVO(category.getId(), category.getName(), category.getSlug(),
                category.getDescription(), category.getAccent(), category.getSortOrder(), 0L);
    }

    @Transactional
    public TaxonomyDtos.CategoryVO updateCategory(Long id, TaxonomyDtos.CategorySaveRequest request) {
        Category existing = categoryMapper.selectById(id);
        if (existing == null) throw BizException.notFound("分类");
        existing.setName(request.name().trim());
        if (request.slug() != null && !request.slug().isBlank()) existing.setSlug(request.slug().trim());
        existing.setDescription(request.description());
        if (request.accent() != null && !request.accent().isBlank()) existing.setAccent(request.accent());
        if (request.sortOrder() != null) existing.setSortOrder(request.sortOrder());
        categoryMapper.updateById(existing);
        invalidate();
        return new TaxonomyDtos.CategoryVO(existing.getId(), existing.getName(), existing.getSlug(),
                existing.getDescription(), existing.getAccent(), existing.getSortOrder(), 0L);
    }

    @Transactional
    public void deleteCategory(Long id) {
        Category existing = categoryMapper.selectById(id);
        if (existing == null) throw BizException.notFound("分类");
        long used = categoryMapper.countPostsUsing(id);
        if (used > 0) throw BizException.conflict("该分类下还有 " + used + " 篇文章，无法删除");
        categoryMapper.deleteById(id);
        invalidate();
    }

    /** Bumping the taxonomy namespace retires every cached list without touching Redis keys directly. */
    public void invalidate() {
        cache.bumpVersion("taxonomy");
        cache.bumpVersion("post");
    }

    private String resolveSlug(String provided, String name, String prefix) {
        String slug = provided == null || provided.isBlank() ? SlugUtils.slugify(name, prefix) : provided.trim();
        return SlugUtils.slugify(slug, prefix);
    }
}
