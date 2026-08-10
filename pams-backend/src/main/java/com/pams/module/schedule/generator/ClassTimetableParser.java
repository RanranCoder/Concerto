package com.pams.module.schedule.generator;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
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
    private static final Pattern COURSE = Pattern.compile("\\(([^()]*)\\)\\s*\\[[\\d,\\s]+\\]");
    private static final Pattern SEMESTER = Pattern.compile("\\b(\\d{4}-\\d{4}-\\d)\\b");
    private static final String[] DAY_NAMES = {"星期一","星期二","星期三","星期四","星期五","星期六","星期日"};

    private ClassTimetableParser() {}

    public static Map<SlotKey, List<Course>> parse(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            return parseSheet(wb.getSheetAt(0));
        }
    }

    public static String detectSemester(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Matcher m = SEMESTER.matcher(wb.getSheetAt(0).getSheetName());
            return m.find() ? m.group(1) : null;
        }
    }

    private static Map<SlotKey, List<Course>> parseSheet(Sheet sheet) {
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
                if (DAY_NAMES[d - 1].equals(v)) dayCol.put(d, c);
            }
        }

        Map<SlotKey, List<Course>> result = new HashMap<>();
        for (int period = 1; period <= 6; period++) {
            Row row = sheet.getRow(headerRow + period);
            if (row == null) continue;
            for (int d = 1; d <= 7; d++) {
                Integer col = dayCol.get(d);
                if (col == null) continue;
                List<Course> courses = parseCell(fmt.formatCellValue(row.getCell(col)));
                if (!courses.isEmpty()) result.put(new SlotKey(d, period), courses);
            }
        }
        return result;
    }

    private static List<Course> parseCell(String text) {
        List<Course> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = COURSE.matcher(trimmed);
            if (!m.find()) continue;
            String name = trimmed.substring(0, m.start()).trim();
            if (name.isEmpty()) name = "未知课程";
            out.add(new Course(name, WeekRangeParser.parse(m.group(1).trim(), 18)));
        }
        return out;
    }
}
