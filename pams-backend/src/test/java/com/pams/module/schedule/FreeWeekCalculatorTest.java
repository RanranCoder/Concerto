package com.pams.module.schedule;

import com.pams.module.schedule.generator.Course;
import com.pams.module.schedule.generator.FreeWeekCalculator;
import com.pams.module.schedule.generator.SlotKey;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FreeWeekCalculatorTest {

    private static Set<Integer> w(int... vals) {
        Set<Integer> s = new HashSet<>();
        for (int v : vals) s.add(v);
        return s;
    }

    @Test
    void freeIsComplementOfUnion() {
        List<Course> courses = List.of(new Course("A", w(1,2,3,4)), new Course("B", w(4,5,6)));
        assertThat(FreeWeekCalculator.freeWeeksForSlot(courses, 18))
                .isEqualTo(w(7,8,9,10,11,12,13,14,15,16,17,18));
    }

    @Test
    void emptyCourses_allFree() {
        assertThat(FreeWeekCalculator.freeWeeksForSlot(List.of(), 18))
                .isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18));
    }

    @Test
    void computeCoversAllSlots() {
        Map<SlotKey, List<Course>> tt = Map.of(new SlotKey(1, 1), List.of(new Course("A", w(1))));
        Map<SlotKey, Set<Integer>> free = FreeWeekCalculator.compute(tt, 18);
        assertThat(free.get(new SlotKey(1, 1))).doesNotContain(1);
        assertThat(free.get(new SlotKey(3, 5)))
                .isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18));
    }
}
