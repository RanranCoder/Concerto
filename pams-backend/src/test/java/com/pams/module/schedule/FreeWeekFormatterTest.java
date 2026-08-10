package com.pams.module.schedule;

import com.pams.module.schedule.generator.FreeWeekFormatter;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FreeWeekFormatterTest {

    private static Set<Integer> w(int... vals) {
        Set<Integer> s = new HashSet<>();
        for (int v : vals) s.add(v);
        return s;
    }

    @Test
    void empty_isZero() {
        assertThat(FreeWeekFormatter.format(Set.of())).isEqualTo("0");
    }

    @Test
    void contiguous() {
        assertThat(FreeWeekFormatter.format(w(1,2,3,4))).isEqualTo("1-4");
    }

    @Test
    void oddRun() {
        assertThat(FreeWeekFormatter.format(w(9,11,13,15))).isEqualTo("9-15 单");
    }

    @Test
    void evenRun() {
        assertThat(FreeWeekFormatter.format(w(2,4,6,8))).isEqualTo("2-8 双");
    }

    @Test
    void contiguousThenEvenRun() {
        assertThat(FreeWeekFormatter.format(w(1,2,4,6))).isEqualTo("1-2,4-6 双");
    }

    @Test
    void oddRunThenContiguous() {
        assertThat(FreeWeekFormatter.format(w(9,11,13,15,17,18))).isEqualTo("9-17 单,18");
    }

    @Test
    void full() {
        assertThat(FreeWeekFormatter.format(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18))).isEqualTo("1-18");
    }

    @Test
    void scattered() {
        assertThat(FreeWeekFormatter.format(w(3,7))).isEqualTo("3,7");
    }
}
