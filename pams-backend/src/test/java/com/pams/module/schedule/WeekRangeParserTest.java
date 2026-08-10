package com.pams.module.schedule;

import com.pams.module.schedule.generator.WeekRangeParser;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WeekRangeParserTest {

    private static Set<Integer> w(int... vals) {
        Set<Integer> s = new HashSet<>();
        for (int v : vals) s.add(v);
        return s;
    }

    @Test
    void fullRange() {
        assertThat(WeekRangeParser.parse("1-16", 18)).isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16));
    }

    @Test
    void singleOddWeeks() {
        assertThat(WeekRangeParser.parse("1-13 单", 18)).isEqualTo(w(1,3,5,7,9,11,13));
    }

    @Test
    void singleEvenWeeks() {
        assertThat(WeekRangeParser.parse("2-18 双", 18)).isEqualTo(w(2,4,6,8,10,12,14,16,18));
    }

    @Test
    void specificWeeksSemicolon() {
        assertThat(WeekRangeParser.parse("3;7", 18)).isEqualTo(w(3,7));
    }

    @Test
    void specificWeeksComma() {
        assertThat(WeekRangeParser.parse("1,3,5", 18)).isEqualTo(w(1,3,5));
    }

    @Test
    void mixedSegments() {
        assertThat(WeekRangeParser.parse("3-6;11-18", 18)).isEqualTo(w(3,4,5,6,11,12,13,14,15,16,17,18));
    }

    @Test
    void mixedWithParity() {
        assertThat(WeekRangeParser.parse("1-4;9-18", 18)).isEqualTo(w(1,2,3,4,9,10,11,12,13,14,15,16,17,18));
    }

    @Test
    void singleWeek() {
        assertThat(WeekRangeParser.parse("8", 18)).isEqualTo(w(8));
    }

    @Test
    void truncateAtMaxWeek() {
        assertThat(WeekRangeParser.parse("1-20", 18)).isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18));
    }

    @Test
    void blank_returnsEmpty() {
        assertThat(WeekRangeParser.parse("", 18)).isEmpty();
    }
}
