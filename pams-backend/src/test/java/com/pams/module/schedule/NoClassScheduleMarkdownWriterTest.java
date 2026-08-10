package com.pams.module.schedule;

import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleMarkdownWriter;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoClassScheduleMarkdownWriterTest {

    @Test
    void writesMarkdownTable() throws Exception {
        var tt = ClassTimetableParser.parse(new ByteArrayInputStream(ClassTimetableParserTest.buildTimetable()));
        List<NoClassScheduleRow> rows = NoClassScheduleGenerator.build(List.of(new PersonTimetable("张三", tt)), 18);
        String md = NoClassScheduleMarkdownWriter.write(rows, "文秘部 无课表");

        assertThat(md).contains("# 文秘部 无课表");
        assertThat(md).contains("| 节次 | 星期一 | 星期二 | 星期三 | 星期四 | 星期五 |");
        assertThat(md).contains("| 第一二节 | 张三（17-18） | 张三（2-14 双,15-18） | 张三（1-17 单） |");
        assertThat(md).contains("| 第十一十二节 | 张三（1-18） | 张三（1-18） | 张三（1-18） | 张三（1-7,9-18）");
    }
}
