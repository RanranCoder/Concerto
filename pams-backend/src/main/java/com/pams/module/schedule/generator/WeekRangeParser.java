package com.pams.module.schedule.generator;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析课程周次字符串为有课周集合。
 * 支持 "1-16"、"1-13 单"、"2-18 双"、"3;7"、"1,3,5"、"3-6;11-18"、"1-4;9-18"、"8"；
 * 按 maxWeek 截断（超过的周丢弃）。
 */
public final class WeekRangeParser {

    private static final Pattern SEG = Pattern.compile("(\\d+)(?:\\s*-\\s*(\\d+))?\\s*周?\\s*([单双]周?)?");

    private WeekRangeParser() {}

    public static Set<Integer> parse(String weekText, int maxWeek) {
        return parse(weekText, maxWeek, false);
    }

    static Set<Integer> parseCourseWeeks(String weekText, int maxWeek) {
        return parse(weekText, maxWeek, true);
    }

    private static Set<Integer> parse(String weekText, int maxWeek, boolean courseWeeks) {
        Set<Integer> result = new HashSet<>();
        if (weekText == null || weekText.isBlank()) {
            if (courseWeeks) throw new IllegalArgumentException("课程缺少周次");
            return result;
        }
        for (String seg : weekText.replace(',', ';').replace('、', ';').replaceAll("[–—~至]", "-").split(";", -1)) {
            seg = seg.trim();
            Matcher m = SEG.matcher(seg);
            if (!m.matches()) {
                if (courseWeeks) throw new IllegalArgumentException("无法识别课程周次：" + weekText);
                continue;
            }
            int a = Integer.parseInt(m.group(1));
            int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
            if (courseWeeks && (a == 0 || b == 0)) throw new IllegalArgumentException("课程周次须从第 1 周开始");
            if (b < a) { int t = a; a = b; b = t; }
            String parity = m.group(3);
            for (int w = a; w <= b && w <= maxWeek; w++) {
                if (parity == null) result.add(w);
                else if (parity.startsWith("单") && w % 2 == 1) result.add(w);
                else if (parity.startsWith("双") && w % 2 == 0) result.add(w);
            }
        }
        return result;
    }
}
