package com.qichi.blog.modules.sponsor.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.sponsor.SponsorDtos;
import com.qichi.blog.modules.sponsor.service.SponsorMethodService;
import com.qichi.blog.modules.sponsor.service.SponsorService;
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
@RequestMapping("/api/v1/admin/sponsors")
@Tag(name = "后台 · 赞助")
public class AdminSponsorController {

    private final SponsorService sponsorService;
    private final SponsorMethodService methodService;

    public AdminSponsorController(SponsorService sponsorService, SponsorMethodService methodService) {
        this.sponsorService = sponsorService;
        this.methodService = methodService;
    }

    // ------------------------------------------------------------------ 赞助方式

    @GetMapping("/methods")
    @Operation(summary = "全部赞助方式")
    public R<List<SponsorDtos.SponsorMethodVO>> methods() {
        return R.ok(methodService.list());
    }

    @PostMapping("/methods")
    @Operation(summary = "新增一条赞助方式（文本 / 链接 / 图片）")
    public R<SponsorDtos.SponsorMethodVO> createMethod(@Valid @RequestBody SponsorDtos.SponsorMethodRequest request) {
        return R.ok(methodService.create(request));
    }

    @PutMapping("/methods/{id}")
    @Operation(summary = "修改一条赞助方式")
    public R<SponsorDtos.SponsorMethodVO> updateMethod(@PathVariable Long id,
                                                       @Valid @RequestBody SponsorDtos.SponsorMethodRequest request) {
        return R.ok(methodService.update(id, request));
    }

    @DeleteMapping("/methods/{id}")
    @Operation(summary = "删除一条赞助方式")
    public R<Void> deleteMethod(@PathVariable Long id) {
        methodService.delete(id);
        return R.ok();
    }

    // ------------------------------------------------------------------ 赞助名单

    @GetMapping
    @Operation(summary = "全部赞助记录，含隐藏的")
    public R<List<SponsorDtos.SponsorVO>> list() {
        return R.ok(sponsorService.listAll());
    }

    @PostMapping
    @Operation(summary = "新增一条赞助记录")
    public R<SponsorDtos.SponsorVO> create(@Valid @RequestBody SponsorDtos.SponsorRequest request) {
        return R.ok(sponsorService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改赞助记录")
    public R<SponsorDtos.SponsorVO> update(@PathVariable Long id, @Valid @RequestBody SponsorDtos.SponsorRequest request) {
        return R.ok(sponsorService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除赞助记录")
    public R<Void> delete(@PathVariable Long id) {
        sponsorService.delete(id);
        return R.ok();
    }
}
