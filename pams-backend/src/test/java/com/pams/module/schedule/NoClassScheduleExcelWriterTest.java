package com.pams.module.schedule;

import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.NoClassScheduleExcelWriter;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoClassScheduleExcelWriterTest {

    private static List<NoClassScheduleRow> grid() throws Exception {
        var tt = ClassTimetableParser.parse(new ByteArrayInputStream(ClassTimetableParserTest.buildTimetable()));
        return NoClassScheduleGenerator.build(List.of(new PersonTimetable("张三", tt)), 18);
    }

    @Test
    void writesExpectedLayout() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NoClassScheduleExcelWriter.write(grid(), "文秘部 无课表", out);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("文秘部");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("星期一");
            Row p1 = sheet.getRow(2);
            assertThat(p1.getCell(1).getStringCellValue()).isEqualTo("第一二节");
            assertThat(p1.getCell(2).getStringCellValue()).contains("张三").contains("17-18");
            Row p6 = sheet.getRow(7);
            assertThat(p6.getCell(1).getStringCellValue()).isEqualTo("第十一十二节");
            assertThat(p6.getCell(5).getStringCellValue()).contains("1-7,9-18");
        }
    }
}
