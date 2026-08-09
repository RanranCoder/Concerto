package com.pams.module.schedule.generator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每(星期,节次)时段的空闲周 = 全集{1..maxWeek} - 该时段所有课程有课周并集。 */
public final class FreeWeekCalculator {

    private FreeWeekCalculator() {}

    public static Set<Integer> freeWeeksForSlot(List<Course> courses, int maxWeek) {
        Set<Integer> all = new HashSet<>();
        for (int w = 1; w <= maxWeek; w++) all.add(w);
        for (Course c : courses) all.removeAll(c.weeks());
        return all;
    }

    /** 返回每(星期,节次)的空闲周集合（7 天 × 6 节全覆盖，缺省时段视为全集空闲）。 */
    public static Map<SlotKey, Set<Integer>> compute(Map<SlotKey, List<Course>> timetable, int maxWeek) {
        Map<SlotKey, Set<Integer>> result = new HashMap<>();
        for (int day = 1; day <= 7; day++) {
            for (int period = 1; period <= 6; period++) {
                SlotKey key = new SlotKey(day, period);
                result.put(key, freeWeeksForSlot(timetable.getOrDefault(key, List.of()), maxWeek));
            }
        }
        return result;
    }
}
