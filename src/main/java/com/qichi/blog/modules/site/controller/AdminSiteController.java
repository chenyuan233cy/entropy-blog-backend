package com.qichi.blog.modules.site.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.site.SiteDtos;
import com.qichi.blog.modules.site.service.SiteConfigService;
import com.qichi.blog.modules.user.UserDtos;
import com.qichi.blog.modules.user.service.UserService;
import com.qichi.blog.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "后台 · 站点配置与账号")
public class AdminSiteController {

    private final SiteConfigService configService;
    private final UserService userService;
    private final com.qichi.blog.modules.user.service.OAuthService oAuthService;

    public AdminSiteController(SiteConfigService configService, UserService userService,
                               com.qichi.blog.modules.user.service.OAuthService oAuthService) {
        this.configService = configService;
        this.userService = userService;
        this.oAuthService = oAuthService;
    }

    @GetMapping("/stats")
    @Operation(summary = "后台概览统计")
    public R<SiteDtos.SiteStatsVO> stats() {
        return R.ok(configService.stats());
    }

    @GetMapping("/logins")
    @Operation(summary = "登录方式总览：每个平台填没填密钥、开关开没开、多少人绑着")
    public R<List<java.util.Map<String, Object>>> logins() {
        return R.ok(oAuthService.adminOverview());
    }

    @GetMapping("/config")
    @Operation(summary = "配置项，按分组返回并附带类型")
    public R<List<SiteDtos.ConfigGroupVO>> config() {
        return R.ok(configService.adminConfig());
    }

    @PutMapping("/config")
    @Operation(summary = "批量更新配置，更新后缓存立即失效")
    public R<Void> update(@Valid @RequestBody SiteDtos.ConfigUpdateRequest request) {
        configService.update(request.items());
        return R.ok();
    }

    @GetMapping("/profile")
    @Operation(summary = "作者资料")
    public R<UserDtos.UserProfileVO> profile() {
        return R.ok(userService.toProfile(userService.requireById(SecurityUtils.requireUser().id())));
    }

    @PutMapping("/profile")
    @Operation(summary = "更新作者资料")
    public R<UserDtos.UserProfileVO> updateProfile(@Valid @RequestBody UserDtos.ProfileUpdateRequest request) {
        return R.ok(userService.updateProfile(SecurityUtils.requireUser().id(), request));
    }

    @PutMapping("/profile/password")
    @Operation(summary = "修改密码")
    public R<Void> changePassword(@Valid @RequestBody UserDtos.PasswordChangeRequest request) {
        userService.changePassword(SecurityUtils.requireUser().id(), request);
        return R.ok();
    }
}
