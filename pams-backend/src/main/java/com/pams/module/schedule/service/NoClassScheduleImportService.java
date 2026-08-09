package com.pams.module.schedule.service;

import com.pams.common.BizException;
import com.pams.entity.Department;
import com.pams.module.schedule.dto.ImportFileFailureVO;
import com.pams.module.schedule.dto.NoClassScheduleCellVO;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.dto.NoClassScheduleRowVO;
import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.Course;
import com.pams.module.schedule.generator.NoClassScheduleExcelWriter;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleMarkdownWriter;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import com.pams.module.schedule.generator.SlotKey;
import com.pams.module.schedule.generator.TimetableNameExtractor;
import com.pams.repository.DepartmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 批量导入班级课表生成无课表：解析 -> 计算 -> 写 xlsx/markdown 到统一输出目录 -> 组装结果 VO。 */
@Service
public class NoClassScheduleImportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 学期参数格式：如 2025-2026-2。用于阻断把非法 semester 拼进输出文件名造成的路径穿越写入。 */
    private static final Pattern SEMESTER_PATTERN = Pattern.compile("\\d{4}-\\d{4}-\\d");

    private final DepartmentRepository departmentRepository;
    private String uploadDir;

    public NoClassScheduleImportService(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @Autowired
    public void setUploadDir(@Value("${pams.upload-dir:./uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public NoClassScheduleImportVO importTimetables(List<MultipartFile> files, Long deptId, String semester, Integer maxWeek) {
        int max = maxWeek == null || maxWeek < 1 || maxWeek > 30 ? 18 : maxWeek;
        String deptName = deptName(deptId);
        if (files == null || files.isEmpty()) throw new BizException(2702, "请至少上传一个课表文件");
        if (semester != null && !semester.isBlank() && !SEMESTER_PATTERN.matcher(semester).matches()) {
            throw new BizException(2703, "学期格式非法");
        }

        List<PersonTimetable> people = new ArrayList<>();
        List<ImportFileFailureVO> failed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (MultipartFile f : files) {
            String filename = f.getOriginalFilename() == null ? "未命名" : f.getOriginalFilename();
            String name = TimetableNameExtractor.extractName(filename);
            if (name == null) {
                failed.add(failure(filename, "无法从文件名识别姓名，请按「姓名-…」命名"));
                continue;
            }
            if (f.isEmpty()) { failed.add(failure(filename, "文件为空")); continue; }
            String lower = filename.toLowerCase();
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
                failed.add(failure(filename, "仅支持 .xlsx/.xls"));
                continue;
            }
            try (InputStream in = f.getInputStream()) {
                String detected = ClassTimetableParser.detectSemester(in);
                if (semester != null && !semester.isBlank() && detected != null && !semester.equals(detected)) {
                    warnings.add(filename + " 的学期(" + detected + ")与所选(" + semester + ")不一致，按所选学期输出");
                }
            } catch (Exception ignored) {
                // 学期检测失败不阻塞
            }
            try (InputStream in = f.getInputStream()) {
                Map<SlotKey, List<Course>> tt = ClassTimetableParser.parse(in);
                people.add(new PersonTimetable(name, tt));
            } catch (IllegalArgumentException e) {
                failed.add(failure(filename, e.getMessage()));
            } catch (Exception e) {
                // 单个文件解析异常（损坏 zip、数字解析失败等）只记失败，不中断整批导入
                failed.add(failure(filename, "文件解析失败: " + e.getMessage()));
            }
        }

        List<NoClassScheduleRow> rows = people.isEmpty() ? List.of() : NoClassScheduleGenerator.build(people, max);
        String markdown = "";
        String downloadUrl = null;
        if (!people.isEmpty()) {
            Path dir = Path.of(uploadDir == null ? "uploads" : uploadDir, "无课表");
            try { Files.createDirectories(dir); } catch (IOException e) {
                throw new BizException(2704, "输出目录创建失败");
            }
            String safeSem = semester == null || semester.isBlank() ? "未指定学期" : semester;
            String base = "无课表_" + deptName + "_" + safeSem + "_" + LocalDateTime.now().format(STAMP);
            Path xlsxPath = dir.resolve(base + ".xlsx");
            try (java.io.OutputStream out = Files.newOutputStream(xlsxPath)) {
                NoClassScheduleExcelWriter.write(rows, deptName + " 无课表", out);
            } catch (IOException e) {
                throw new BizException(2704, "生成 Excel 失败");
            }
            markdown = NoClassScheduleMarkdownWriter.write(rows, deptName + " 无课表");
            downloadUrl = "无课表/" + xlsxPath.getFileName().toString();
        }

        return toVO(deptName, semester, rows, markdown, downloadUrl, files.size(), people.size(), failed, warnings);
    }

    /** 校验下载路径归一化后位于 uploadDir 内，返回绝对路径。 */
    public Path resolveDownload(String path) {
        if (path == null || path.isBlank()) throw new BizException(2705, "非法路径");
        Path root = Path.of(uploadDir == null ? "uploads" : uploadDir).toAbsolutePath().normalize();
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) throw new BizException(2705, "非法路径");
        return target;
    }

    private NoClassScheduleImportVO toVO(String deptName, String semester, List<NoClassScheduleRow> rows,
                                         String markdown, String downloadUrl, int total, int success,
                                         List<ImportFileFailureVO> failed, List<String> warnings) {
        NoClassScheduleImportVO vo = new NoClassScheduleImportVO();
        vo.setDeptName(deptName);
        vo.setSemester(semester);
        vo.setRows(rows.stream().map(r -> {
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
        }).toList());
        vo.setMarkdown(markdown);
        vo.setDownloadUrl(downloadUrl);
        vo.setTotalFiles(total);
        vo.setSuccessCount(success);
        vo.setFailed(failed);
        vo.setWarnings(warnings);
        return vo;
    }

    private String deptName(Long deptId) {
        if (deptId == null) return "未分配";
        return departmentRepository.findById(deptId).map(Department::getName).orElse("未分配");
    }

    private ImportFileFailureVO failure(String fileName, String reason) {
        ImportFileFailureVO vo = new ImportFileFailureVO();
        vo.setFileName(fileName);
        vo.setReason(reason);
        return vo;
    }
}
