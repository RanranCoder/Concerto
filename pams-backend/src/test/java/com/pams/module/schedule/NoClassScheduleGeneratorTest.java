package com.pams.module.schedule;

import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.Course;
import com.pams.module.schedule.generator.NoClassScheduleCell;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import com.pams.module.schedule.generator.SlotKey;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoClassScheduleGeneratorTest {

    @Test
    void buildsGridFromParsedTimetable() throws Exception {
        Map<SlotKey, List<Course>> tt = ClassTimetableParser.parse(new ByteArrayInputStream(ClassTimetableParserTest.buildTimetable()));
        List<NoClassScheduleRow> rows = NoClassScheduleGenerator.build(List.of(new PersonTimetable("张三", tt)), 18);

        assertThat(rows).hasSize(6);
        NoClassScheduleRow p1 = rows.get(0);
        assertThat(p1.label()).isEqualTo("第一二节");
        assertThat(p1.halfDay()).isEqualTo("上午");
        // 周一第1-2节：课程A(1-16) -> 空闲 17-18
        assertThat(p1.cells().get(1)).containsExactly(new NoClassScheduleCell("张三", "17-18"));
        // 周二第1-2节：课程B(1-13 单) -> 空闲 2-14 双,15-18
        assertThat(p1.cells().get(2)).containsExactly(new NoClassScheduleCell("张三", "2-14 双,15-18"));
        // 周三第1-2节：课程C(2-18 双) -> 空闲 1-17 单
        assertThat(p1.cells().get(3)).containsExactly(new NoClassScheduleCell("张三", "1-17 单"));
        // 周五第3-4节：课程F(1-18) -> 满课 0
        assertThat(rows.get(1).cells().get(5)).containsExactly(new NoClassScheduleCell("张三", "0"));
        // 周一第9-10节：公共选修课(1-16) -> 17-18
        assertThat(rows.get(4).cells().get(1)).containsExactly(new NoClassScheduleCell("张三", "17-18"));
        // 周四第11-12节：课程I(8) -> 1-7,9-18
        assertThat(rows.get(5).cells().get(4)).containsExactly(new NoClassScheduleCell("张三", "1-7,9-18"));
    }

    @Test
    void sortsCellsByNameAscending() {
        Map<SlotKey, List<Course>> empty = new HashMap<>();
        List<NoClassScheduleRow> rows = NoClassScheduleGenerator.build(
                List.of(new PersonTimetable("李四", empty), new PersonTimetable("张三", empty)), 18);
        // "张"(U+5F20) < "李"(U+674E)，故张三在前
        assertThat(rows.get(0).cells().get(1))
                .containsExactly(new NoClassScheduleCell("张三", "1-18"), new NoClassScheduleCell("李四", "1-18"));
    }
}
