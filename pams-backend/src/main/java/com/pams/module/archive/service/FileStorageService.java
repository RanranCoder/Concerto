package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.repository.FileRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {
    /**
     * 上传扩展名白名单（PRD-v5 S4，替代单一黑名单）：Office 文档 / 图片 / 文本 / 压缩包。
     * 仍保留危险扩展名黑名单作第二层防线——白名单收紧新增类型时黑名单兜底不失效。
     * html/htm/svg 不在白名单（存储型 XSS），exe/脚本类一律拒绝。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // Office 文档
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "csv", "txt", "md",
            // 图片
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            // 压缩包（归档材料）
            "zip", "rar", "7z");

    /** 危险/可执行扩展名黑名单：上传文件的二次防线，防止白名单遗漏时兜底拒绝 */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "jsp", "jspx", "war", "php", "php3", "sh", "bat", "cmd", "ps1", "html", "htm", "svg");

    private final Path uploadDir;
    private final FileRecordRepository fileRecordRepository;

    @Autowired
    public FileStorageService(@Value("${pams.upload-dir}") String uploadDir,
                              FileRecordRepository fileRecordRepository) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.fileRecordRepository = fileRecordRepository;
    }
    public FileStorageService(Path uploadDir) {
        this.uploadDir = uploadDir.toAbsolutePath().normalize();
        this.fileRecordRepository = null;
    }

    /**
     * 清洗文件名：过滤控制字符（如 NUL、DEL，Windows 文件系统非法）、~$ 临时前缀、路径穿越。
     * 保留安全字符：中英文/数字/空格/连字符/下划线/点。
     * 注意：控制字符过滤必须先于 Path.of，否则 Windows 上 Path.of 直接抛 InvalidPathException。
     */
    public String sanitize(String filename) {
        String base = filename == null ? "unnamed" : filename;
        // 移除 C0 控制字符（\x00-\x1F）与 DEL（\x7F）；空格/连字符/下划线/点等不在范围内，不会被误删
        base = base.replaceAll("[\\x00-\\x1F\\x7F]", "");
        base = Path.of(base).getFileName().toString();
        while (base.startsWith("~$")) base = base.substring(2);
        return base;
    }

    public String bizTypeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("策划") || lower.contains("方案")) return "PLAN";
        if (lower.contains("签到")) return "SIGNIN";
        if (lower.contains("排班") || lower.contains("安排") || lower.contains("值班")) return "SCHEDULE";
        if (lower.contains("考勤")) return "ATTENDANCE";
        if (lower.contains("新闻")) return "NEWS";
        if (lower.contains("推文")) return "ARTICLE";
        if (lower.contains("发票")) return "INVOICE";
        if (lower.contains(".jpg") || lower.contains(".png")) return "PHOTO";
        if (lower.contains(".ppt")) return "PPT";
        return "OTHER";
    }

    /** 由 file_record.path（如 2026-08-02/uuid-name）解析出磁盘绝对路径，并做路径穿越防护 */
    public Path physicalPath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new BizException(4001, "文件路径为空");
        }
        Path resolved = uploadDir.resolve(storedPath).normalize();
        if (!resolved.startsWith(uploadDir)) {
            throw new BizException(4001, "非法的文件路径");
        }
        return resolved;
    }

    public FileRecord store(MultipartFile file, String bizType, Long uploaderId) {
        String original = sanitize(file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        // 扩展名判定（基于 sanitize 后的文件名）
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        // 白名单优先（S4）：不在允许清单的类型直接拒绝
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BizException(4002, "不允许上传该文件类型");
        }
        // 黑名单二次防线：白名单调整误放行时兜底
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            throw new BizException(4002, "不允许上传该文件类型");
        }
        String sub = LocalDate.now().toString();
        String storedName = UUID.randomUUID().toString().replace("-", "") + "-" + original;
        try {
            Path dir = uploadDir.resolve(sub);
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName);
            file.transferTo(target);
            FileRecord rec = new FileRecord();
            rec.setFilename(original);
            rec.setStoredName(storedName);
            rec.setPath(sub + "/" + storedName);
            rec.setSize(file.getSize());
            rec.setContentType(file.getContentType());
            rec.setBizType(bizType == null ? bizTypeOf(original) : bizType);
            rec.setUploaderId(uploaderId);
            return fileRecordRepository.save(rec);
        } catch (IOException e) {
            throw new BizException(4001, "文件保存失败");
        }
    }
}
