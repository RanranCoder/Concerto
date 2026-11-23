package com.pams.module.schedule.generator;

import com.pams.common.ExcelGuard;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取班级课表 Excel（"节次"表头 + 周一到周日列 + 6 行节次），解析出每(星期,节次)的课程列表。
 * 单元格格式：课程名(周次)[节次]◇教室◇教师，多课用换行分隔。
 */
public final class ClassTimetableParser {

    /** 取紧跟 "[数字,数字]" 的括号内容作为周次，防课程名内括号干扰。 */
    private static final Pattern COURSE = Pattern.compile("\\(([^()]*)\\)\\s*\\[[\\d,、\\s-]+节?\\]");
    private static final Pattern SEMESTER =
            Pattern.compile("(?<!\\d)(\\d{4}-\\d{4})\\s*(?:-(\\d)|学年\\s*第\\s*(\\d)\\s*学期)(?!\\d)");
    private static final String[] DAY_NAMES = {"星期一","星期二","星期三","星期四","星期五","星期六","星期日"};

    public record ParsedTimetable(String semester, Map<SlotKey, List<Course>> courses) {}

    private ClassTimetableParser() {}

    public static Map<SlotKey, List<Course>> parse(InputStream in) throws IOException {
        return parse(in, 18);
    }

    /** 按学期总周数 maxWeek 截断解析，与无课表生成的 maxWeek 口径一致，避免周数不一致。 */
    public static Map<SlotKey, List<Course>> parse(InputStream in, int maxWeek) throws IOException {
        return parseTimetable(in, maxWeek).courses();
    }

    public static ParsedTimetable parseTimetable(InputStream in, int maxWeek) throws IOException {
        try (Workbook wb = ExcelGuard.open(in)) {
            Sheet sheet = wb.getSheetAt(0);
            return new ParsedTimetable(detectSemester(sheet), parseSheet(sheet, maxWeek));
        }
    }

    public static String detectSemester(InputStream in) throws IOException {
        try (Workbook wb = ExcelGuard.open(in)) {
            return detectSemester(wb.getSheetAt(0));
        }
    }

    private static String detectSemester(Sheet sheet) {
        String semester = semesterIn(sheet.getSheetName());
        if (semester != null) return semester;
        DataFormatter formatter = new DataFormatter();
        for (int r = 0; r <= Math.min(5, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (var cell : row) {
                semester = semesterIn(formatter.formatCellValue(cell));
                if (semester != null) return semester;
            }
        }
        return null;
    }

    private static String semesterIn(String text) {
        Matcher match = SEMESTER.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        if (!match.find()) return null;
        return match.group(1) + "-" + (match.group(2) == null ? match.group(3) : match.group(2));
    }

    private static Map<SlotKey, List<Course>> parseSheet(Sheet sheet, int maxWeek) {
        DataFormatter fmt = new DataFormatter();
        int headerRow = -1;
        for (int r = 0; r <= Math.min(5, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                if ("节次".equals(fmt.formatCellValue(row.getCell(c)).trim())) { headerRow = r; break; }
            }
            if (headerRow >= 0) break;
        }
        if (headerRow < 0) throw new IllegalArgumentException("未找到“节次”表头，不是班级课表格式");

        Map<Integer, Integer> dayCol = new HashMap<>();
        Row hdr = sheet.getRow(headerRow);
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            String v = fmt.formatCellValue(hdr.getCell(c)).trim();
            for (int d = 1; d <= 7; d++) {
                if ((DAY_NAMES[d - 1].equals(v) || ("周" + DAY_NAMES[d - 1].substring(2)).equals(v))
                        && dayCol.putIfAbsent(d, c) != null) {
                    throw new IllegalArgumentException("发现重复列：" + DAY_NAMES[d - 1]);
                }
            }
        }
        for (int d = 1; d <= 5; d++) {
            if (!dayCol.containsKey(d)) throw new IllegalArgumentException("缺少课表列：" + DAY_NAMES[d - 1]);
        }

        Map<SlotKey, List<Course>> result = new HashMap<>();
        for (int period = 1; period <= 6; period++) {
            Row row = sheet.getRow(headerRow + period);
            if (row == null) continue;
            for (int d = 1; d <= 7; d++) {
                Integer col = dayCol.get(d);
                if (col == null) continue;
                try {
                    List<Course> courses = parseCell(fmt.formatCellValue(row.getCell(col)), maxWeek);
                    if (!courses.isEmpty()) result.put(new SlotKey(d, period), courses);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(DAY_NAMES[d - 1] + NoClassScheduleGenerator.PERIOD_LABELS[period - 1]
                            + "：" + e.getMessage(), e);
                }
            }
        }
        return result;
    }

    private static List<Course> parseCell(String text, int maxWeek) {
        List<Course> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String line : text.split("\\R")) {
            String trimmed = Normalizer.normalize(line, Normalizer.Form.NFKC).trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = COURSE.matcher(trimmed);
            if (!m.find()) throw new IllegalArgumentException("无法识别课程格式，应为“课程名(周次)[节次]”");
            String name = trimmed.substring(0, m.start()).trim();
            if (name.isEmpty()) name = "未知课程";
            out.add(new Course(name, WeekRangeParser.parseCourseWeeks(m.group(1), maxWeek)));
        }
        return out;
    }
}
