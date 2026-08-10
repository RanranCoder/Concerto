package com.pams.module.schedule;

import com.pams.module.schedule.generator.TimetableNameExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimetableNameExtractorTest {

    @Test
    void dashFile() {
        assertThat(TimetableNameExtractor.extractName("张子睿-文件-2025物联网3班-班级课表.xlsx")).isEqualTo("张子睿");
    }

    @Test
    void underscore() {
        assertThat(TimetableNameExtractor.extractName("刘如倩_2025计应2班-班级课表.xlsx")).isEqualTo("刘如倩");
    }

    @Test
    void dashNoFile() {
        assertThat(TimetableNameExtractor.extractName("罗展标-2025软件技术2班.xlsx")).isEqualTo("罗展标");
    }

    @Test
    void leadingChinese() {
        assertThat(TimetableNameExtractor.extractName("凌健锦2025软件3班.xlsx")).isEqualTo("凌健锦");
    }

    @Test
    void leadingChineseFour() {
        assertThat(TimetableNameExtractor.extractName("司徒锦豪2025物联网3班-班级课表.xlsx")).isEqualTo("司徒锦豪");
    }

    @Test
    void personalFormat() {
        assertThat(TimetableNameExtractor.extractName("吴文烨-文件-25计应创新2班吴文烨.xlsx")).isEqualTo("吴文烨");
    }

    @Test
    void unparseable_returnsNull() {
        assertThat(TimetableNameExtractor.extractName("2025物联网3班.xlsx")).isNull();
    }
}
