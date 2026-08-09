package com.pams.module.schedule.generator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 把多个人的课程表组装成"无课表"网格：6 行节次 x 周一~周五，每格列出每人空闲周。 */
public final class NoClassScheduleGenerator {

    public static final String[] PERIOD_LABELS = {"第一二节","第三四节","第五六节","第七八节","第九十节","第十一十二节"};
    public static final String[] HALF_DAYS = {"上午","上午","下午","下午","晚上","晚上"};

    private NoClassScheduleGenerator() {}

    public static List<NoClassScheduleRow> build(List<PersonTimetable> people, int maxWeek) {
        List<NoClassScheduleRow> rows = new ArrayList<>();
        for (int period = 1; period <= 6; period++) {
            Map<Integer, List<NoClassScheduleCell>> cells = new TreeMap<>();
            for (int day = 1; day <= 5; day++) {
                List<NoClassScheduleCell> dayCells = new ArrayList<>();
                for (PersonTimetable p : people) {
                    List<Course> courses = p.timetable().getOrDefault(new SlotKey(day, period), List.of());
                    String text = FreeWeekFormatter.format(FreeWeekCalculator.freeWeeksForSlot(courses, maxWeek));
                    dayCells.add(new NoClassScheduleCell(p.name(), text));
                }
                dayCells.sort(Comparator.comparing(NoClassScheduleCell::name));
                cells.put(day, dayCells);
            }
            rows.add(new NoClassScheduleRow(period, PERIOD_LABELS[period - 1], HALF_DAYS[period - 1], cells));
        }
        return rows;
    }
}
