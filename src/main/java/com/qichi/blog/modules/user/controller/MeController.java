package com.qichi.blog.modules.user.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.upload.UploadService;
import com.qichi.blog.modules.user.UserDtos;
import com.qichi.blog.modules.user.service.UserResourceService;
import com.qichi.blog.modules.user.service.UserService;
import com.qichi.blog.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The signed-in reader's own account. Everything here acts on the caller and only on the caller,
 * so it lives outside /admin — a reader editing their nickname must never need an admin token.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "我的账号")
public class MeController {

    private final UserService userService;
    private final UserResourceService resourceService;

    public MeController(UserService userService, UserResourceService resourceService) {
        this.userService = userService;
        this.resourceService = resourceService;
    }

    @GetMapping
    @Operation(summary = "当前登录用户的资料")
    public R<UserDtos.UserProfileVO> profile() {
        return R.ok(userService.toProfile(userService.requireById(SecurityUtils.requireUser().id())));
    }

    @PutMapping
    @Operation(summary = "更新自己的资料")
    public R<UserDtos.UserProfileVO> update(@Valid @RequestBody UserDtos.ProfileUpdateRequest request) {
        return R.ok(userService.updateProfile(SecurityUtils.requireUser().id(), request));
    }

    @PostMapping("/avatar")
    @Operation(summary = "上传头像，落到 users/<随机串>/resources/avatar/ 下。只影响自己")
    public R<Map<String, String>> avatar(@RequestParam("file") MultipartFile file) {
        long userId = SecurityUtils.requireUser().id();
        return R.ok(Map.of("url", resourceService.storeAvatar(file, userId)));
    }

    @PutMapping("/password")
    @Operation(summary = "修改自己的密码")
    public R<Void> changePassword(@Valid @RequestBody UserDtos.PasswordChangeRequest request) {
        userService.changePassword(SecurityUtils.requireUser().id(), request);
        return R.ok();
    }

    // ------------------------------------------------------------------ 我的资源

    @GetMapping("/resources")
    @Operation(summary = "列出自己的资源（头像、导出的资料……）")
    public R<List<UserResourceService.ResourceVO>> resources() {
        return R.ok(resourceService.list(SecurityUtils.requireUser().id()));
    }

    @PostMapping("/resources/export")
    @Operation(summary = "把自己的资料打包成 json 存进资源目录，返回这个文件")
    public R<UserResourceService.ResourceVO> export() {
        long userId = SecurityUtils.requireUser().id();
        return R.ok(resourceService.exportProfile(userId, userService.toProfile(userService.requireById(userId))));
    }

    @GetMapping("/resources/download")
    @Operation(summary = "下载自己的某个资源。路径只能是自己的目录，拿不到别人的")
    public ResponseEntity<Resource> download(@RequestParam String path) {
        long userId = SecurityUtils.requireUser().id();
        UserResourceService.Resolved resolved = resourceService.resolve(userId, path);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(resolved.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(resolved.mime()))
                .contentLength(resolved.size())
                .body(new FileSystemResource(resolved.path()));
    }
}
