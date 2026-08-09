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

    private static final Pattern SEG = Pattern.compile("(\\d+)(?:-(\\d+))?\\s*(单|双)?");

    private WeekRangeParser() {}

    public static Set<Integer> parse(String weekText, int maxWeek) {
        Set<Integer> result = new HashSet<>();
        if (weekText == null || weekText.isBlank()) return result;
        for (String seg : weekText.replace(',', ';').split(";")) {
            seg = seg.trim();
            if (seg.isEmpty()) continue;
            Matcher m = SEG.matcher(seg);
            if (!m.matches()) continue;
            int a = Integer.parseInt(m.group(1));
            int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
            if (b < a) { int t = a; a = b; b = t; }
            String parity = m.group(3);
            for (int w = a; w <= b && w <= maxWeek; w++) {
                if (parity == null) result.add(w);
                else if ("单".equals(parity) && w % 2 == 1) result.add(w);
                else if ("双".equals(parity) && w % 2 == 0) result.add(w);
            }
        }
        return result;
    }
}
