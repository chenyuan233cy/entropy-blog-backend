package com.qichi.blog.modules.site.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.site.SiteDtos;
import com.qichi.blog.modules.site.service.SiteConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/site")
@Tag(name = "站点")
public class SiteController {

    private final SiteConfigService configService;

    public SiteController(SiteConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/config")
    @Operation(summary = "站点配置，类型已还原")
    public R<Map<String, Object>> config() {
        return R.ok(configService.publicConfig());
    }

    @GetMapping("/stats")
    @Operation(summary = "站点统计")
    public R<SiteDtos.SiteStatsVO> stats() {
        return R.ok(configService.stats());
    }
}
