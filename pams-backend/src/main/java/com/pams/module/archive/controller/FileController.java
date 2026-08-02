package com.pams.module.archive.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.service.FileStorageService;
import com.pams.module.party.service.RosterImportService;
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
    private final RosterImportService rosterImportService;

    public FileController(FileStorageService storageService, FileRecordRepository fileRecordRepository,
                          RosterImportService rosterImportService) {
        this.storageService = storageService;
        this.fileRecordRepository = fileRecordRepository;
        this.rosterImportService = rosterImportService;
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
     * 导入名单（xlsx），按 rosterType 逐行写入 party_roster。
     * 基于原始文件名判断 ~$ Office 临时文件：sanitize 会剥离 ~$ 前缀，
     * 若先 sanitize 再判断会变成永不触发的死代码，故此处用原始文件名。
     */
    @PostMapping("/import")
    public Result<Integer> importRoster(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "type", defaultValue = "ACTIVE") String type) {
        String original = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        if (original.startsWith("~$")) {
            throw new BizException(4001, "请勿上传 Office 临时文件");
        }
        String filename = storageService.sanitize(original);
        if (!filename.toLowerCase().endsWith(".xlsx") && !filename.toLowerCase().endsWith(".xls")) {
            throw new BizException(4001, "仅支持 xlsx/xls 名单文件");
        }
        if (file.isEmpty()) {
            throw new BizException(4001, "文件为空");
        }
        int count;
        try (java.io.InputStream in = file.getInputStream()) {
            count = rosterImportService.importFromXlsx(in, type);
        } catch (java.io.IOException e) {
            throw new BizException(4001, "名单文件读取失败");
        }
        return Result.ok(count);
    }
}
