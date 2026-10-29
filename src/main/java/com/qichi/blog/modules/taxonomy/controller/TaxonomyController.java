package com.qichi.blog.modules.taxonomy.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.taxonomy.TaxonomyDtos;
import com.qichi.blog.modules.taxonomy.service.TaxonomyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "标签与分类")
public class TaxonomyController {

    private final TaxonomyService taxonomyService;

    public TaxonomyController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @GetMapping("/tags")
    @Operation(summary = "标签列表，带文章计数")
    public R<List<TaxonomyDtos.TagVO>> tags() {
        return R.ok(taxonomyService.listTags());
    }

    @GetMapping("/categories")
    @Operation(summary = "分类列表，带文章计数")
    public R<List<TaxonomyDtos.CategoryVO>> categories() {
        return R.ok(taxonomyService.listCategories());
    }
}
