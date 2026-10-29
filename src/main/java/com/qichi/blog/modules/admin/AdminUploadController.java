package com.qichi.blog.modules.admin;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.upload.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/upload")
@Tag(name = "后台 · 上传")
public class AdminUploadController {

    private final UploadService uploadService;

    public AdminUploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping
    @Operation(summary = "上传图片，返回可直接使用的相对地址")
    public R<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        return R.ok(Map.of("url", uploadService.store(file), "name", original));
    }
}
