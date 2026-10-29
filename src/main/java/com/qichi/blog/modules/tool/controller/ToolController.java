package com.qichi.blog.modules.tool.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.tool.ToolDtos;
import com.qichi.blog.modules.tool.service.ToolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 工具页的公开部分：谁都能浏览目录，下载要登录（见 SecurityConfig 里那条更窄的规则）。
 */
@RestController
@RequestMapping("/api/v1/tools")
@Tag(name = "工具")
public class ToolController {

    private final ToolService toolService;

    public ToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping
    @Operation(summary = "列出某一层目录，parentId 为 0 或省略即根目录")
    public R<ToolDtos.Listing> listing(@RequestParam(required = false) Long parentId) {
        return R.ok(toolService.listing(parentId));
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "下载文件，需要登录，会累计下载次数")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        ToolService.Download download = toolService.prepareDownload(id);
        // 文件名用 RFC 5987 编码，中文名才不会在响应头里变成乱码
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.name(), StandardCharsets.UTF_8)
                .build();
        MediaType type = download.mime() == null || download.mime().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(download.mime());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(type)
                .contentLength(download.size())
                .body(new FileSystemResource(download.path()));
    }
}
