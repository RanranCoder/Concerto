package com.pams.module.party.service;

import com.pams.common.BizException;
import com.pams.module.party.entity.PartyRoster;
import com.pams.module.party.repository.PartyRosterRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 入党积极分子等名单 xlsx 导入 party_roster。
 *
 * 支持的表头结构（简报中的"入党积极分子名单"）：序号/学号/姓名/性别/民族/班级/支部/备注，
 * 通过表头文字匹配定位列，因此列顺序可调、多余列（民族/备注等）自动忽略。
 * 表头以"姓名/学号"为识别依据，可跳过文件首部的标题行（如"学校党校培训班学员信息表"）。
 */
@Service
public class RosterImportService {
    private final PartyRosterRepository rosterRepo;

    public RosterImportService(PartyRosterRepository rosterRepo) {
        this.rosterRepo = rosterRepo;
    }

    @Transactional
    public int importFromXlsx(InputStream in, String rosterType) {
        List<PartyRoster> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = firstDataSheet(wb);
            Row header = findHeaderRow(sheet);
            Map<String, Integer> col = locateColumns(header);

            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) {
                    continue;
                }
                String name = cellStr(r.getCell(col.get("name")));
                String studentNo = cellStr(r.getCell(col.get("studentNo")));
                if (isBlank(name) && isBlank(studentNo)) {
                    continue; // 空行 / 表格末尾合并行
                }
                if (isBlank(name)) {
                    continue; // 无姓名不成名单
                }
                PartyRoster pr = new PartyRoster();
                pr.setRosterType(rosterType);
                pr.setName(name);
                pr.setGender(cellStr(r.getCell(col.get("gender"))));
                pr.setStudentNo(studentNo);
                pr.setClassName(cellStr(r.getCell(col.get("className"))));
                pr.setBranchName(cellStr(r.getCell(col.get("branchName"))));
                pr.setCreatedAt(LocalDateTime.now());
                rows.add(pr);
            }
        } catch (IOException e) {
            throw new BizException(4001, "名单文件解析失败");
        }
        rosterRepo.saveAll(rows);
        return rows.size();
    }

    private Sheet firstDataSheet(Workbook wb) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            if (s.getLastRowNum() >= 0 && s.getRow(0) != null) {
                return s;
            }
        }
        throw new BizException(4001, "名单文件为空");
    }

    /** 在前 N 行内定位表头行（包含"姓名"或"学号"文字），可跳过标题行。 */
    private Row findHeaderRow(Sheet sheet) {
        int last = Math.min(sheet.getLastRowNum(), 9);
        for (int i = 0; i <= last; i++) {
            Row r = sheet.getRow(i);
            if (r == null) {
                continue;
            }
            for (Cell c : r) {
                String norm = norm(cellStr(c));
                if (norm != null && (norm.contains("姓名") || norm.contains("学号"))) {
                    return r;
                }
            }
        }
        throw new BizException(4001, "名单文件缺少表头（需含姓名/学号列）");
    }

    /** 表头文字匹配列位置。姓名/学号必填，性别/班级/支部可选。 */
    private Map<String, Integer> locateColumns(Row header) {
        Map<String, Integer> col = new HashMap<>();
        for (Cell c : header) {
            String norm = norm(cellStr(c));
            if (norm == null) {
                continue;
            }
            if (!col.containsKey("name") && norm.contains("姓名")) {
                col.put("name", c.getColumnIndex());
            } else if (!col.containsKey("studentNo") && norm.contains("学号")) {
                col.put("studentNo", c.getColumnIndex());
            } else if (!col.containsKey("gender") && norm.contains("性别")) {
                col.put("gender", c.getColumnIndex());
            } else if (!col.containsKey("className") && norm.contains("班级")) {
                col.put("className", c.getColumnIndex());
            } else if (!col.containsKey("branchName") && norm.contains("支部")) {
                col.put("branchName", c.getColumnIndex());
            }
        }
        if (!col.containsKey("name") || !col.containsKey("studentNo")) {
            throw new BizException(4001, "名单文件缺少姓名或学号列");
        }
        return col;
    }

    /** 单元格转字符串：去空格，数字避免科学计数法（学号可能是数字单元格）。 */
    private String cellStr(Cell c) {
        if (c == null) {
            return null;
        }
        switch (c.getCellType()) {
            case STRING:
                String v = c.getStringCellValue();
                return v == null ? null : v.trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(c)) {
                    return new DataFormatter().formatCellValue(c);
                }
                double d = c.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                    return Long.toString((long) d);
                }
                return Double.toString(d);
            case BOOLEAN:
                return Boolean.toString(c.getBooleanCellValue());
            case FORMULA:
                try {
                    String fv = c.getStringCellValue();
                    return fv == null ? null : fv.trim();
                } catch (IllegalStateException e) {
                    double dv = c.getNumericCellValue();
                    return Double.toString(dv);
                }
            default:
                return null;
        }
    }

    private String norm(String s) {
        return s == null ? null : s.replaceAll("\\s+", "");
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
