package com.pams.module.archive.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.service.FileStorageService;
import com.pams.security.LoginUser;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final FileStorageService storageService;
    private final FileRecordRepository fileRecordRepository;

    public FileController(FileStorageService storageService, FileRecordRepository fileRecordRepository) {
        this.storageService = storageService;
        this.fileRecordRepository = fileRecordRepository;
    }

    @PostMapping("/upload")
    public Result<FileRecord> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(required = false) String bizType,
                                     @AuthenticationPrincipal LoginUser current) {
        Long uploaderId = current == null ? null : current.getId();
        FileRecord rec = storageService.store(file, bizType, uploaderId);
        return Result.ok(rec);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        FileRecord rec = fileRecordRepository.findById(id)
                .orElseThrow(() -> new BizException(4001, "文件不存在"));
        Path target = storageService.physicalPath(rec.getPath());
        if (!Files.exists(target)) {
            throw new BizException(4001, "文件不存在或已被移除");
        }
        try {
            Resource resource = new UrlResource(target.toUri());
            String encoded = java.net.URLEncoder.encode(rec.getFilename(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            rec.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : rec.getContentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                    .body(resource);
        } catch (Exception e) {
            throw new BizException(4001, "文件读取失败");
        }
    }

    /**
     * 导入入党积极分子名单（xlsx）。本任务先做骨架：仅做临时文件过滤与类型校验，
     * 具体解析入库逻辑由 Task 24（存量材料迁移）实现。
     */
    @PostMapping("/import")
    public Result<Void> importRoster(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "type", defaultValue = "ROSTER_ACTIVE") String type) {
        String filename = storageService.sanitize(
                file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        if (filename.startsWith("~$")) {
            throw new BizException(4001, "请勿上传 Office 临时文件");
        }
        if (!filename.toLowerCase().endsWith(".xlsx") && !filename.toLowerCase().endsWith(".xls")) {
            throw new BizException(4001, "仅支持 xlsx/xls 名单文件");
        }
        if (file.isEmpty()) {
            throw new BizException(4001, "文件为空");
        }
        // TODO(Task 24): 解析 xlsx，按 roster_type=RECOMMEND 写入 party_roster
        throw new BizException(4001, "名单解析尚未实现（Task 24 迁移脚本提供）");
    }
}
