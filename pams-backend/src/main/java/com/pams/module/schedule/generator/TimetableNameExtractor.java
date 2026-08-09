package com.pams.module.schedule.generator;

import java.util.regex.Pattern;

/** 从文件名提取干事姓名：优先「姓名-文件-」，其次「姓名_/-」，最后前导中文。提取失败返回 null。 */
public final class TimetableNameExtractor {

    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(xlsx|xls)$");
    private static final Pattern DASH_FILE = Pattern.compile("^(.*?)-文件-");
    private static final Pattern LEADING_CN = Pattern.compile("^[\\u4e00-\\u9fa5·]{2,4}");

    private TimetableNameExtractor() {}

    public static String extractName(String filename) {
        if (filename == null) return null;
        String base = SUFFIX.matcher(filename.trim()).replaceFirst("");
        var m = DASH_FILE.matcher(base);
        if (m.find() && looksLikeName(m.group(1).trim())) return m.group(1).trim();
        int cut = -1;
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if (ch == '-' || ch == '_') { cut = i; break; }
        }
        if (cut > 0) {
            String first = base.substring(0, cut).trim();
            if (looksLikeName(first)) return first;
        }
        var lm = LEADING_CN.matcher(base);
        if (lm.find() && looksLikeName(lm.group())) return lm.group();
        return null;
    }

    private static boolean looksLikeName(String s) {
        return s != null && !s.isEmpty() && s.length() <= 6 && s.matches("[\\u4e00-\\u9fa5·]+");
    }
}
