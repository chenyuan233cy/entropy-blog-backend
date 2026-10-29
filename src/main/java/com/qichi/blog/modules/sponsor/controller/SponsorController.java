package com.qichi.blog.modules.sponsor.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.sponsor.SponsorDtos;
import com.qichi.blog.modules.sponsor.service.SponsorMethodService;
import com.qichi.blog.modules.sponsor.service.SponsorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sponsors")
@Tag(name = "赞助")
public class SponsorController {

    private final SponsorService sponsorService;
    private final SponsorMethodService methodService;

    public SponsorController(SponsorService sponsorService, SponsorMethodService methodService) {
        this.sponsorService = sponsorService;
        this.methodService = methodService;
    }

    @GetMapping
    @Operation(summary = "公开的赞助名单，只含站长选择展示的")
    public R<List<SponsorDtos.SponsorVO>> list() {
        return R.ok(sponsorService.listVisible());
    }

    @GetMapping("/methods")
    @Operation(summary = "赞助方式：文本 / 链接 / 图片。一条都没有就返回空数组，前台显示「暂无」")
    public R<List<SponsorDtos.SponsorMethodVO>> methods() {
        return R.ok(methodService.list());
    }
}
