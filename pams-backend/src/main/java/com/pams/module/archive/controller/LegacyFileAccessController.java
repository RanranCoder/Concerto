package com.pams.module.archive.controller;

import com.pams.common.BizException;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.service.FileService;
import com.pams.security.LoginUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** Old image URLs pass the same object-level check as the download endpoint. */
@RestController
public class LegacyFileAccessController {
    private final FileRecordRepository records;
    private final FileService files;
    public LegacyFileAccessController(FileRecordRepository records,FileService files) {
        this.records=records; this.files=files;
    }
    @GetMapping("/uploads/{*path}")
    public ResponseEntity<Void> download(@PathVariable String path,@AuthenticationPrincipal LoginUser user) {
        var rec=records.findFirstByPath(path.startsWith("/")?path.substring(1):path)
                .orElseThrow(() -> new BizException(404,"文件不存在"));
        try { files.getReadableRecord(rec.getId(),user); }
        catch(BizException e) { throw new BizException(404,"文件不存在"); }
        return ResponseEntity.status(302).location(URI.create("/api/files/"+rec.getId()+"/download")).build();
    }
}
