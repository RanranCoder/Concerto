package com.pams.module.schedule.service;

import com.pams.config.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.common.ExcelGuard;
import com.pams.common.OrgContext;
import com.pams.entity.Department;
import com.pams.module.notification.service.BusinessNoticeService;
import com.pams.module.schedule.dto.ImportFileFailureVO;
import com.pams.module.schedule.dto.NoClassScheduleCellVO;
import com.pams.module.schedule.dto.NoClassScheduleGeneratedVO;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.dto.NoClassScheduleRowVO;
import com.pams.module.schedule.entity.NoClassScheduleRecord;
import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.NoClassScheduleExcelWriter;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleMarkdownWriter;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import com.pams.module.schedule.generator.TimetableNameExtractor;
import com.pams.module.schedule.repository.NoClassScheduleRecordRepository;
import com.pams.repository.DepartmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** 批量导入班级课表，生成预览并发布通过校验的批次。 */
@Slf4j
@Service
public class NoClassScheduleImportService {
    @Autowired
    private BusinessNoticeService notices;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String EXPORT_FOLDER = "无课表";
    private static final Pattern SEMESTER_PATTERN = Pattern.compile("\\d{4}-\\d{4}-\\d");
    private static final Pattern EXPORT_FILENAME =
            Pattern.compile("无课表_\\d{4}-\\d{4}-\\d_[0-9a-f-]{36}\\.xlsx");

    private final DepartmentRepository departmentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NoClassScheduleRecordRepository recordRepository;
    private String uploadDir;

    public NoClassScheduleImportService(DepartmentRepository departmentRepository,
                                        NoClassScheduleRecordRepository recordRepository) {
        this.departmentRepository = departmentRepository;
        this.recordRepository = recordRepository;
    }

    @Autowired
    public void setUploadDir(@Value("${pams.upload-dir:./uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Transactional
    public NoClassScheduleImportVO importTimetables(List<MultipartFile> files, Long deptId, String semester, Integer maxWeek) {
        if (files == null || files.isEmpty()) throw new BizException(2702, "请至少上传一个课表文件");
        if (semester == null || semester.isBlank()) throw new BizException(2703, "请选择学期");
        String selectedSemester = semester.strip();
        if (!SEMESTER_PATTERN.matcher(selectedSemester).matches()) {
            throw new BizException(2703, "学期格式应为 2025-2026-2");
        }
        if (deptId == null) throw new BizException(2703, "请选择部门");
        Long orgId = OrgContext.getOrDefault();
        String deptName = departmentRepository.findById(deptId)
                .filter(dept -> Objects.equals(dept.getOrgId(), orgId))
                .map(Department::getName)
                .orElseThrow(() -> new BizException(2703, "请选择当前组织的有效部门"));
        int max = maxWeek == null || maxWeek < 1 || maxWeek > 30 ? 18 : maxWeek;

        List<PersonTimetable> people = new ArrayList<>();
        List<ImportFileFailureVO> failed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile f = files.get(i);
            String filename = f.getOriginalFilename() == null ? "未命名" : f.getOriginalFilename();
            String name = TimetableNameExtractor.extractName(filename);
            if (name == null) {
                failed.add(failure(i, filename, "无法从文件名识别姓名，请按「姓名-…」命名"));
                continue;
            }
            if (f.isEmpty()) {
                failed.add(failure(i, filename, "文件为空"));
                continue;
            }
            String lower = filename.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
                failed.add(failure(i, filename, "仅支持 .xlsx/.xls"));
                continue;
            }
            ClassTimetableParser.ParsedTimetable timetable;
            try (InputStream in = f.getInputStream()) {
                ExcelGuard.checkSize(f.getSize());
                timetable = ClassTimetableParser.parseTimetable(in, max);
            } catch (BizException | IllegalArgumentException e) {
                failed.add(failure(i, filename, e.getMessage()));
                continue;
            } catch (Exception e) {
                log.warn("无课表文件解析失败，文件序号 {}", i + 1, e);
                failed.add(failure(i, filename, "文件无法读取，请重新导出课表后上传"));
                continue;
            }
            String detected = timetable.semester();
            if (detected != null && !selectedSemester.equals(detected)) {
                warnings.add(filename + " 的学期(" + detected + ")与所选(" + selectedSemester + ")不一致，按所选学期输出");
            }
            people.add(new PersonTimetable(name, timetable.courses()));
        }

        List<NoClassScheduleRow> rows = people.isEmpty() ? List.of() : NoClassScheduleGenerator.build(people, max);
        List<NoClassScheduleRowVO> rowVOs = toRowVOs(rows);
        boolean published = !people.isEmpty() && failed.isEmpty();
        String markdown = "";
        String downloadUrl = null;
        if (!people.isEmpty()) {
            String title = deptName + " 无课表";
            markdown = NoClassScheduleMarkdownWriter.write(rows, title);
            downloadUrl = writeExcel(orgId, selectedSemester, title, rows);
            if (published) persistRecord(orgId, deptId, deptName, selectedSemester, rowVOs);
        }

        if (notices != null) {
            var domain = BusinessNoticeService.Domain.SCHEDULE_IMPORT;
            notices.add(domain, published ? BusinessNoticeService.Action.IMPORTED : BusinessNoticeService.Action.SKIPPED,
                    people.size(), null);
            notices.add(domain, BusinessNoticeService.Action.FAILED, failed.size(), null);
        }
        var result = toVO(deptName, selectedSemester, rowVOs, markdown, downloadUrl, files.size(), people.size(), failed, warnings);
        result.setDeptId(deptId);
        result.setPublished(published);
        return result;
    }

    private void persistRecord(Long orgId, Long deptId, String deptName, String semester, List<NoClassScheduleRowVO> rows) {
        try {
            String grid = objectMapper.writeValueAsString(rows);
            LocalDateTime now = LocalDateTime.now();
            if (jdbc != null && SqlDialect.postgresql(jdbc)) {
                recordRepository.saveLatestPostgresql(orgId, deptId, deptName, semester, grid, now);
            } else {
                recordRepository.saveLatest(orgId, deptId, deptName, semester, grid, now);
            }
        } catch (Exception e) {
            log.error("无课表保存失败，组织 {}，部门 {}，学期 {}", orgId, deptId, semester, e);
            throw new BizException(2704, "无课表保存失败，请重试");
        }
    }

    /** 读取某部门+学期最新生成的无课表；无记录或 semester 为空返回 null。 */
    public NoClassScheduleGeneratedVO getGenerated(Long deptId, String semester) {
        if (deptId == null || semester == null || semester.isBlank()) return null;
        Optional<NoClassScheduleRecord> opt = recordRepository.findByDeptIdAndSemester(deptId, semester.strip());
        if (opt.isEmpty()) return null;
        NoClassScheduleRecord rec = opt.get();
        try {
            List<NoClassScheduleRowVO> rows = objectMapper.readValue(rec.getGridJson(), new TypeReference<>() {});
            NoClassScheduleGeneratedVO vo = new NoClassScheduleGeneratedVO();
            vo.setDeptName(rec.getDeptName());
            vo.setSemester(rec.getSemester());
            vo.setRows(rows);
            vo.setCreatedAt(rec.getCreatedAt());
            return vo;
        } catch (Exception e) {
            log.error("无课表读取失败，记录 {}", rec.getId(), e);
            throw new BizException(2704, "无课表读取失败，请重试");
        }
    }

    /** 最近一次导入的无课表 {deptId, deptName, semester}；无则 null。 */
    public Map<String, Object> latestImport(Long deptId) {
        var latest = deptId == null ? recordRepository.findFirstByOrderByCreatedAtDescIdDesc()
                : recordRepository.findFirstByDeptIdOrderByCreatedAtDescIdDesc(deptId);
        if (latest.isEmpty()) return null;
        var record = latest.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deptId", record.getDeptId());
        result.put("deptName", record.getDeptName());
        result.put("semester", record.getSemester());
        return result;
    }

    public List<String> generatedSemesters() {
        return recordRepository.findDistinctSemesters();
    }

    public Path resolveDownload(String path) {
        if (path == null || path.isBlank()) throw new BizException(2705, "非法路径");
        Long orgId = OrgContext.getOrDefault();
        Path relative;
        try {
            relative = Path.of(path);
        } catch (InvalidPathException e) {
            throw new BizException(2705, "非法路径");
        }
        if (relative.isAbsolute() || relative.getNameCount() != 3
                || !EXPORT_FOLDER.equals(relative.getName(0).toString())
                || !orgId.toString().equals(relative.getName(1).toString())
                || !EXPORT_FILENAME.matcher(relative.getFileName().toString()).matches()) {
            throw new BizException(2705, "非法路径");
        }
        Path target = exportDirectory(orgId).resolve(relative.getFileName());
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new BizException(2705, "文件不存在");
        return target;
    }

    private String writeExcel(Long orgId, String semester, String title, List<NoClassScheduleRow> rows) {
        Path dir = exportDirectory(orgId);
        String filename = "无课表_" + semester + "_" + UUID.randomUUID() + ".xlsx";
        Path target = dir.resolve(filename);
        boolean created = false;
        try {
            Files.createDirectories(dir);
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                NoClassScheduleExcelWriter.write(rows, title, out);
            }
        } catch (IOException | RuntimeException e) {
            if (created) deleteExport(target);
            log.error("无课表 Excel 生成失败，组织 {}，学期 {}", orgId, semester, e);
            throw new BizException(2704, "生成 Excel 失败，请重试");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) deleteExport(target);
                }
            });
        }
        return EXPORT_FOLDER + "/" + orgId + "/" + filename;
    }

    private Path exportDirectory(Long orgId) {
        return Path.of(uploadDir == null ? "uploads" : uploadDir, EXPORT_FOLDER, orgId.toString())
                .toAbsolutePath().normalize();
    }

    private void deleteExport(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("清理未完成的无课表 Excel 失败：{}", target.getFileName(), e);
        }
    }

    private NoClassScheduleImportVO toVO(String deptName, String semester, List<NoClassScheduleRowVO> rows,
                                         String markdown, String downloadUrl, int total, int success,
                                         List<ImportFileFailureVO> failed, List<String> warnings) {
        NoClassScheduleImportVO vo = new NoClassScheduleImportVO();
        vo.setDeptName(deptName);
        vo.setSemester(semester);
        vo.setRows(rows);
        vo.setMarkdown(markdown);
        vo.setDownloadUrl(downloadUrl);
        vo.setTotalFiles(total);
        vo.setSuccessCount(success);
        vo.setFailed(failed);
        vo.setWarnings(warnings);
        return vo;
    }

    private List<NoClassScheduleRowVO> toRowVOs(List<NoClassScheduleRow> rows) {
        return rows.stream().map(r -> {
            NoClassScheduleRowVO rv = new NoClassScheduleRowVO();
            rv.setPeriod(r.period());
            rv.setLabel(r.label());
            rv.setHalfDay(r.halfDay());
            Map<String, List<NoClassScheduleCellVO>> days = new LinkedHashMap<>();
            r.cells().forEach((day, cells) -> days.put(String.valueOf(day),
                    cells.stream().map(c -> {
                        NoClassScheduleCellVO cv = new NoClassScheduleCellVO();
                        cv.setName(c.name());
                        cv.setFreeWeeks(c.freeWeeks());
                        return cv;
                    }).toList()));
            rv.setDays(days);
            return rv;
        }).toList();
    }

    private ImportFileFailureVO failure(int fileIndex, String fileName, String reason) {
        ImportFileFailureVO vo = new ImportFileFailureVO();
        vo.setFileIndex(fileIndex);
        vo.setFileName(fileName);
        vo.setReason(reason);
        return vo;
    }
}
