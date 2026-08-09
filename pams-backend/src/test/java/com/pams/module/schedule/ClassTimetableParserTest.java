package com.pams.module.schedule;

import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.Course;
import com.pams.module.schedule.generator.SlotKey;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassTimetableParserTest {

    static Set<Integer> w(int... vals) {
        Set<Integer> s = new HashSet<>();
        for (int v : vals) s.add(v);
        return s;
    }

    /** 构造与真实班级课表一致的 9 行网格（标题 + 表头 + 周一~周日 + 6 行节次）。 */
    static byte[] buildTimetable() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("2025-2026-2课表");
            String[][] rows = {
                    {"班级代码：25351010203   院系：信息工程学院", null, null, null, "测试一班 课表信息", null, null, "2025-2026学年第2学期", null},
                    {"节次", null, "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"},
                    {"上午", "第1 2节", "课程A(1-16)[1,2]◇1101◇老师甲", "课程B(1-13 单)[1,2]◇2101◇老师乙", "课程C(2-18 双)[1,2]◇3101◇老师丙", null, null, null, null},
                    {"上午", "第3 4节", "课程D(3;7)[3,4]◇1101◇老师甲", "课程E(3-6;11-18)[3,4]◇2101◇老师乙", null, null, "课程F(1-18)[3,4]◇3101◇老师丙", null, null},
                    {"下午", "第5 6节", "课程G(1-4;9-18)[5,6]◇1101◇老师甲", null, null, null, null, null, null},
                    {"下午", "第7 8节", null, "课程H(2-14 双)[7,8]◇2101◇老师乙", null, null, null, null, null},
                    {"晚上", "第9 10节", "公共选修课(1-16)[9,10]◇预占位◇预占位", null, null, null, null, null, null},
                    {"晚上", "第11 12节", null, null, null, "课程I(8)[11,12]◇2101◇老师乙", null, null, null},
                    {"教室编号说明…", null, null, null, null, null, null, "2026-03-06", null},
            };
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) {
                    if (rows[i][j] != null) row.createCell(j).setCellValue(rows[i][j]);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    @Test
    void parsesGrid() throws Exception {
        Map<SlotKey, List<Course>> tt = ClassTimetableParser.parse(new ByteArrayInputStream(buildTimetable()));
        assertThat(tt.get(new SlotKey(1, 1))).hasSize(1);
        assertThat(tt.get(new SlotKey(1, 1)).get(0).name()).isEqualTo("课程A");
        assertThat(tt.get(new SlotKey(1, 1)).get(0).weeks()).isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16));
        assertThat(tt.get(new SlotKey(2, 1)).get(0).weeks()).isEqualTo(w(1,3,5,7,9,11,13));
        assertThat(tt.get(new SlotKey(3, 1)).get(0).weeks()).isEqualTo(w(2,4,6,8,10,12,14,16,18));
        assertThat(tt.get(new SlotKey(2, 2)).get(0).weeks()).isEqualTo(w(3,4,5,6,11,12,13,14,15,16,17,18));
        assertThat(tt.get(new SlotKey(5, 2)).get(0).weeks()).isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18));
        assertThat(tt.get(new SlotKey(4, 6)).get(0).weeks()).isEqualTo(w(8));
    }

    @Test
    void detectsSemester() throws Exception {
        assertThat(ClassTimetableParser.detectSemester(new ByteArrayInputStream(buildTimetable()))).isEqualTo("2025-2026-2");
    }

    @Test
    void throwsOnNonTimetable() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("x").createRow(0).createCell(0).setCellValue("随便");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                assertThrows(IllegalArgumentException.class,
                        () -> ClassTimetableParser.parse(new ByteArrayInputStream(out.toByteArray())));
            }
        }
    }
}
