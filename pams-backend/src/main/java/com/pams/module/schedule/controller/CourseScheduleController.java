package com.pams.module.schedule.controller;

import com.pams.common.BizException;
import com.pams.common.ExcelGuard;
import com.pams.common.Result;
import com.pams.module.schedule.dto.NoClassScheduleGeneratedVO;
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
 * 权限（PRD-v5 S2 收敛为 V7 权限码）：个人课表读写 schedule:free_table（V7 种子：全部角色），
 * 时间格配置与批量导入 schedule:manage（V7 种子：部长级）
 */
@RestController
@RequestMapping("/api/course-schedules")
public class CourseScheduleController {

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

    @PreAuthorize("hasAuthority('schedule:manage')")
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

    /** 批量上传班级课表 xlsx，生成无课表（含下载）。schedule:manage（V7 种子：部长级）。 */
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<NoClassScheduleImportVO> importTimetables(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) Integer maxWeek) {
        if (files != null) {
            for (MultipartFile f : files) {
                ExcelGuard.checkSize(f.getSize());
            }
        }
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

    /** 读取某部门+学期最新生成的无课表（无记录 data=null）。 */
    @GetMapping("/generated")
    public Result<NoClassScheduleGeneratedVO> generated(@RequestParam(required = false) Long deptId,
                                                        @RequestParam(required = false) String semester) {
        return Result.ok(importService.getGenerated(deptId, semester));
    }

    /** 最近一次导入无课表的部门+学期（供前端默认选中；无导入则 null）。 */
    @GetMapping("/generated/latest")
    public Result<Map<String, Object>> generatedLatest() {
        return Result.ok(importService.latestImport());
    }
}
