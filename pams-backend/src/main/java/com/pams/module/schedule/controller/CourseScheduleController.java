package com.pams.module.schedule.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.entity.ScheduleConfig;
import com.pams.module.schedule.service.CourseScheduleService;
import com.pams.module.schedule.service.NoClassScheduleImportService;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * 无课表制作（PRD F08）
 * 权限：schedule:free_table（全部用户可录入自己的课程表；部长+可配置时间格）
 */
@RestController
@RequestMapping("/api/course-schedules")
public class CourseScheduleController {

    private static final String LEADER =
            "hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')";

    private final CourseScheduleService service;
    private final NoClassScheduleImportService importService;

    public CourseScheduleController(CourseScheduleService service, NoClassScheduleImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    // ===== 时间格配置 =====

    @GetMapping("/configs")
    public Result<List<ScheduleConfig>> configs() {
        return Result.ok(service.getConfigs());
    }

    @PreAuthorize(LEADER)
    @PutMapping("/configs")
    public Result<Void> saveConfigs(@RequestBody List<Map<String, Object>> configs) {
        service.saveConfigs(configs);
        return Result.ok();
    }

    // ===== 个人课程表 =====

    /** 查看自己的课程表 */
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(required = false) String semester,
                                            @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.getMySchedule(current.getId(), semester));
    }

    /** 保存自己的课程表（覆盖式）：body {semester, cells:[{dayOfWeek, period, courseName}]} */
    @PostMapping("/mine")
    public Result<Void> saveMine(@RequestBody Map<String, Object> body,
                                 @AuthenticationPrincipal LoginUser current) {
        String semester = (String) body.get("semester");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) body.get("cells");
        service.saveMySchedule(current.getId(), semester, cells);
        return Result.ok();
    }

    // ===== 空闲统计 =====

    /** 生成共同空闲热力图：?semester=xxx&userIds=1,2,3（不传则全员） */
    @GetMapping("/analyze")
    public Result<Map<String, Object>> analyze(@RequestParam(required = false) String semester,
                                               @RequestParam(required = false) List<Long> userIds) {
        return Result.ok(service.analyzeFreeTime(semester, userIds));
    }

    // ===== 批量导入课表生成无课表 =====

    /** 批量上传班级课表 xlsx，生成无课表（含下载）。仅部长及以上。 */
    @PreAuthorize(LEADER)
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<NoClassScheduleImportVO> importTimetables(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) Integer maxWeek) {
        return Result.ok(importService.importTimetables(files == null ? List.of() : List.of(files), deptId, semester, maxWeek));
    }

    /** 下载生成的 xlsx 无课表。path 为上传目录下的相对路径。 */
    @GetMapping("/import/download")
    public ResponseEntity<Resource> download(@RequestParam String path) {
        Path target = importService.resolveDownload(path);
        if (!Files.exists(target)) throw new BizException(2705, "文件不存在");
        try {
            Resource resource = new UrlResource(target.toUri());
            String encoded = URLEncoder.encode(target.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .body(resource);
        } catch (Exception e) {
            throw new BizException(2705, "文件读取失败");
        }
    }
}
