package com.pams.module.archive.controller;

import com.pams.common.BizException;
import com.pams.common.ExcelGuard;
import com.pams.common.Result;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.service.FileService;
import com.pams.module.archive.service.FileStorageService;
import com.pams.module.party.service.RosterImportService;
import com.pams.module.party.service.RosterImportService.RosterImportResult;
import com.pams.security.LoginUser;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final FileStorageService storageService;
    private final FileService fileService;
    private final RosterImportService rosterImportService;

    public FileController(FileStorageService storageService, FileService fileService,
                          RosterImportService rosterImportService) {
        this.storageService = storageService;
        this.fileService = fileService;
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

    /** 下载需材料或党务任一查看权限（名单类文件可能被两侧引用），上传保持登录即可。
     *  审计 #2：类级注解只是粗闸（V7 种子全员持有），对象级/部门级鉴权由 FileService 统一把守
     *  （预览/导出在前端同走本接口，策略一视同仁；越权与不存在同响应防 fileId 枚举） */
    @PreAuthorize("hasAuthority('material:view') or hasAuthority('party:view')")
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @AuthenticationPrincipal LoginUser current) {
        FileRecord rec = fileService.getReadableRecord(id, current);
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
     * 导入名单（xlsx），按 rosterType 逐行写入 party_roster，并按 rosterType+name+studentNo 去重。
     * 返回 {"added":新增条数, "skipped":去重跳过条数}，便于前端/调用方提示"新增 X 条、跳过 Y 条重复"。
     * 基于原始文件名判断 ~$ Office 临时文件：sanitize 会剥离 ~$ 前缀，
     * 若先 sanitize 再判断会变成永不触发的死代码，故此处用原始文件名。
     */
    @PreAuthorize("hasAuthority('party:entry')")
    @PostMapping("/import")
    public Result<Map<String, Integer>> importRoster(@RequestParam("file") MultipartFile file,
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
        ExcelGuard.checkSize(file.getSize());
        RosterImportResult result;
        try (java.io.InputStream in = file.getInputStream()) {
            result = rosterImportService.importFromXlsx(in, type);
        } catch (java.io.IOException e) {
            throw new BizException(4001, "名单文件读取失败");
        }
        return Result.ok(Map.of("added", result.added(), "skipped", result.skipped()));
    }
}
