package com.pams.module.schedule.generator;

import java.util.List;

/** 把无课表网格写成 Markdown 表格，便于对话/文档直接展示。 */
public final class NoClassScheduleMarkdownWriter {

    private NoClassScheduleMarkdownWriter() {}

    public static String write(List<NoClassScheduleRow> rows, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null ? "无课表" : title).append("\n\n");
        sb.append("| 节次 | 星期一 | 星期二 | 星期三 | 星期四 | 星期五 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (NoClassScheduleRow row : rows) {
            sb.append("| ").append(row.label());
            for (int day = 1; day <= 5; day++) {
                sb.append(" | ").append(joinCells(row.cells().get(day)));
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private static String joinCells(List<NoClassScheduleCell> cells) {
        if (cells == null || cells.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (NoClassScheduleCell c : cells) {
            if (sb.length() > 0) sb.append("<br>");
            sb.append(c.name()).append("（").append(c.freeWeeks()).append("）");
        }
        return sb.toString();
    }
}
