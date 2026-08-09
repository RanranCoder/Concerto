package com.pams.module.schedule.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 将无课周集合格式化为展示串。
 * 规则：连续段 "1-4"；步长 2 的段标注 "9-15 单"/"2-8 双"；多段逗号拼接；空集 -> "0"。
 */
public final class FreeWeekFormatter {

    private FreeWeekFormatter() {}

    public static String format(Set<Integer> weeks) {
        List<Integer> list = weeks.stream().sorted().toList();
        if (list.isEmpty()) return "0";
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < list.size()) {
            int j = i; // 最长步长1连续段
            while (j + 1 < list.size() && list.get(j + 1) - list.get(j) == 1) j++;
            int k = i; // 最长步长2连续段
            while (k + 1 < list.size() && list.get(k + 1) - list.get(k) == 2) k++;
            if (k - i >= 1 && k - i >= j - i) {
                String tag = (list.get(i) % 2 == 1) ? " 单" : " 双";
                parts.add(list.get(i) + "-" + list.get(k) + tag);
                i = k + 1;
            } else if (j - i >= 1) {
                parts.add(list.get(i) + "-" + list.get(j));
                i = j + 1;
            } else {
                parts.add(String.valueOf(list.get(i)));
                i++;
            }
        }
        return String.join(",", parts);
    }
}
