package com.qichi.blog.modules.tool.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.tool.ToolDtos;
import com.qichi.blog.modules.tool.service.ToolService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/tools")
@Tag(name = "后台 · 工具")
public class AdminToolController {

    private final ToolService toolService;

    public AdminToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/files")
    @Operation(summary = "全部文件的扁平列表，写作界面挑文件用")
    public R<List<ToolDtos.ToolNodeVO>> files() {
        return R.ok(toolService.allFiles());
    }

    @PostMapping("/folder")
    @Operation(summary = "新建文件夹")
    public R<ToolDtos.ToolNodeVO> folder(@Valid @RequestBody ToolDtos.FolderRequest request) {
        return R.ok(toolService.createFolder(request));
    }

    @PostMapping("/upload")
    @Operation(summary = "上传文件到某个文件夹，parentId 为 0 即根目录")
    public R<ToolDtos.ToolNodeVO> upload(@RequestParam MultipartFile file,
                                         @RequestParam(required = false) Long parentId,
                                         @RequestParam(required = false) String description) {
        return R.ok(toolService.upload(file, parentId, description));
    }

    @PutMapping("/{id}")
    @Operation(summary = "改名 / 改说明")
    public R<Void> rename(@PathVariable Long id, @Valid @RequestBody ToolDtos.RenameRequest request) {
        toolService.rename(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文件夹（连同里面的东西）或单个文件。磁盘文件保留")
    public R<Integer> delete(@PathVariable Long id) {
        return R.ok(toolService.delete(id));
    }
}
