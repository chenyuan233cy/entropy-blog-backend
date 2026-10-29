package com.qichi.blog.modules.taxonomy.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.taxonomy.TaxonomyDtos;
import com.qichi.blog.modules.taxonomy.service.TaxonomyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "后台 · 标签与分类")
public class AdminTaxonomyController {

    private final TaxonomyService taxonomyService;

    public AdminTaxonomyController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @GetMapping("/tags")
    public R<List<TaxonomyDtos.TagVO>> tags() {
        return R.ok(taxonomyService.listTags());
    }

    @PostMapping("/tags")
    @Operation(summary = "新建标签")
    public R<TaxonomyDtos.TagVO> createTag(@Valid @RequestBody TaxonomyDtos.TagSaveRequest request) {
        return R.ok(taxonomyService.createTag(request));
    }

    @PutMapping("/tags/{id}")
    public R<TaxonomyDtos.TagVO> updateTag(@PathVariable Long id, @Valid @RequestBody TaxonomyDtos.TagSaveRequest request) {
        return R.ok(taxonomyService.updateTag(id, request));
    }

    @DeleteMapping("/tags/{id}")
    public R<Void> deleteTag(@PathVariable Long id) {
        taxonomyService.deleteTag(id);
        return R.ok();
    }

    @GetMapping("/categories")
    public R<List<TaxonomyDtos.CategoryVO>> categories() {
        return R.ok(taxonomyService.listCategories());
    }

    @PostMapping("/categories")
    @Operation(summary = "新建分类")
    public R<TaxonomyDtos.CategoryVO> createCategory(@Valid @RequestBody TaxonomyDtos.CategorySaveRequest request) {
        return R.ok(taxonomyService.createCategory(request));
    }

    @PutMapping("/categories/{id}")
    public R<TaxonomyDtos.CategoryVO> updateCategory(@PathVariable Long id, @Valid @RequestBody TaxonomyDtos.CategorySaveRequest request) {
        return R.ok(taxonomyService.updateCategory(id, request));
    }

    @DeleteMapping("/categories/{id}")
    public R<Void> deleteCategory(@PathVariable Long id) {
        taxonomyService.deleteCategory(id);
        return R.ok();
    }
}
