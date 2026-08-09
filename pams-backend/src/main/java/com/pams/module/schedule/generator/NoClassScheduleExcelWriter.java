package com.pams.module.schedule.generator;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** 把无课表网格写成 xlsx（版式与部门现有无课表一致：标题 + 周一~周五 + 6 行节次）。 */
public final class NoClassScheduleExcelWriter {

    private NoClassScheduleExcelWriter() {}

    public static void write(List<NoClassScheduleRow> rows, String title, OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("无课表");

            CellStyle titleStyle = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle cellStyle = wb.createCellStyle();
            cellStyle.setBorderTop(BorderStyle.THIN);
            cellStyle.setBorderBottom(BorderStyle.THIN);
            cellStyle.setBorderLeft(BorderStyle.THIN);
            cellStyle.setBorderRight(BorderStyle.THIN);
            cellStyle.setWrapText(true);
            cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            Row t = sheet.createRow(0);
            Cell tc = t.createCell(0);
            tc.setCellValue(title);
            tc.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

            String[] headers = {"节次", "星期一", "星期二", "星期三", "星期四", "星期五"};
            Row h = sheet.createRow(1);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = h.createCell(c + 1);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(cellStyle);
            }

            int r = 2;
            for (NoClassScheduleRow row : rows) {
                Row rr = sheet.createRow(r);
                rr.createCell(0).setCellValue(row.halfDay());
                rr.createCell(1).setCellValue(row.label());
                for (int day = 1; day <= 5; day++) {
                    List<NoClassScheduleCell> cells = row.cells().get(day);
                    Cell cell = rr.createCell(day + 1);
                    cell.setCellValue(joinCells(cells));
                    cell.setCellStyle(cellStyle);
                }
                r++;
            }

            sheet.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(4, 5, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(6, 7, 0, 0));

            sheet.setColumnWidth(0, 8 * 256);
            for (int c = 1; c <= 6; c++) sheet.setColumnWidth(c, 30 * 256);

            wb.write(out);
        }
    }

    private static String joinCells(List<NoClassScheduleCell> cells) {
        if (cells == null || cells.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (NoClassScheduleCell c : cells) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(c.name()).append("（").append(c.freeWeeks()).append("）");
        }
        return sb.toString();
    }
}
