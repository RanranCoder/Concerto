package com.pams.module.member.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.entity.Department;
import com.pams.module.member.dto.MemberImportResultVO;
import com.pams.module.member.dto.MemberEnums;
import com.pams.module.member.dto.MemberVO;
import com.pams.module.member.entity.Member;
import com.pams.module.member.repository.MemberRepository;
import com.pams.repository.DepartmentRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MemberImportService {
    private final MemberRepository memberRepo;
    private final DepartmentRepository deptRepo;
    private final MemberService memberService;

    public MemberImportService(MemberRepository memberRepo, DepartmentRepository deptRepo) {
        this(memberRepo, deptRepo, null);
    }
    @Autowired
    public MemberImportService(MemberRepository memberRepo, DepartmentRepository deptRepo,
                               MemberService memberService) {
        this.memberRepo = memberRepo; this.deptRepo = deptRepo; this.memberService = memberService;
    }

    /** 模板列（与用户登记表一致）；导出在末尾追加「状态」列 */
    private static final String[] HEADERS = {"序号", "部门", "职位", "姓名", "性别", "学号", "班级", "联系方式", "政治面貌"};
    private static final Set<String> NO_DEPT = Set.of("", "主任", "副主任", "主任室");

    @Transactional
    public MemberImportResultVO importFromXlsx(InputStream in, Long sessionId) {
        if (sessionId == null) throw new BizException(2810, "请选择届别");
        Map<String, Long> deptIdByName = new HashMap<>();
        deptRepo.findAll().forEach(d -> deptIdByName.put(d.getName(), d.getId()));

        List<Member> toSave = new ArrayList<>();
        List<MemberImportResultVO.MemberImportFailureVO> failed = new ArrayList<>();
        List<Member> existing = memberRepo.findBySessionId(sessionId);
        Set<String> seen = new HashSet<>();
        existing.forEach(m -> seen.add(key(sessionId, m.getStudentNo())));

        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = firstDataSheet(wb);
            Row header = findHeaderRow(sheet);
            Map<String, Integer> col = locateColumns(header);

            String lastDeptName = "";
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                String name = cellStr(optionalCell(r, col, "name"));
                String studentNo = cellStr(optionalCell(r, col, "studentNo"));
                if (isBlank(name) && isBlank(studentNo)) continue; // 空行

                String deptName = cellStr(optionalCell(r, col, "dept"));
                if (deptName != null && !deptName.isBlank()) lastDeptName = deptName; // 合并单元格前向填充
                String posLabel = cellStr(optionalCell(r, col, "position"));

                int excelRowNo = i + 1;
                if (isBlank(name)) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, "", "姓名缺失")); continue; }
                String position = MemberEnums.positionOf(posLabel == null ? "" : posLabel);
                if (position == null) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, name, "职位无法识别: " + posLabel)); continue; }
                Long deptId = null;
                String dName = lastDeptName == null ? "" : lastDeptName.trim();
                if (!NO_DEPT.contains(dName)) {
                    deptId = deptIdByName.get(dName);
                    if (deptId == null) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, name, "部门无法识别: " + dName)); continue; }
                }

                String no = studentNo == null ? "" : studentNo.trim();
                if (no.isEmpty()) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, name, "学号缺失（一键建账号需要学号）")); continue; }
                if (!seen.add(key(sessionId, no))) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, name, "学号已存在")); continue; }

                Member m = new Member();
                m.setSessionId(sessionId);
                m.setDeptId(deptId);
                m.setPosition(position);
                m.setName(name);
                m.setGender(cellStr(optionalCell(r, col, "gender")));
                m.setStudentNo(no);
                m.setClassName(cellStr(optionalCell(r, col, "className")));
                m.setPhone(cellStr(optionalCell(r, col, "phone")));
                String pol = cellStr(optionalCell(r, col, "political"));
                m.setPoliticalStatus("团员".equals(pol) ? "共青团员" : pol);
                m.setStatus("ACTIVE");
                m.setCreatedAt(LocalDateTime.now());
                m.setUpdatedAt(LocalDateTime.now());
                toSave.add(m);
            }
        } catch (IOException e) {
            throw new BizException(4001, "名单文件解析失败");
        }

        if (!toSave.isEmpty()) memberRepo.saveAll(toSave);
        int total = toSave.size() + failed.size();
        return new MemberImportResultVO(total, toSave.size(), 0, failed);
    }

    public byte[] buildTemplate() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("成员信息");
            Row h = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) h.createCell(i).setCellValue(HEADERS[i]);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out); return out.toByteArray(); }
        }
    }

    public byte[] exportXlsx(Long sessionId, Long deptId, String position, String status, String keyword) throws IOException {
        PageResult<MemberVO> page = memberService.page(sessionId, deptId, position, status, keyword, 1, 100000);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("成员信息");
            Row h = sheet.createRow(0);
            String[] cols = {"序号", "部门", "职位", "姓名", "性别", "学号", "班级", "联系方式", "政治面貌", "状态"};
            for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);
            List<MemberVO> list = page.getRecords();
            for (int i = 0; i < list.size(); i++) {
                MemberVO m = list.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(m.deptName() == null ? "主任室" : m.deptName());
                row.createCell(2).setCellValue(m.positionLabel());
                row.createCell(3).setCellValue(m.name());
                row.createCell(4).setCellValue(m.gender() == null ? "" : m.gender());
                row.createCell(5).setCellValue(m.studentNo() == null ? "" : m.studentNo());
                row.createCell(6).setCellValue(m.className() == null ? "" : m.className());
                row.createCell(7).setCellValue(m.phone() == null ? "" : m.phone());
                row.createCell(8).setCellValue(m.politicalStatus() == null ? "" : m.politicalStatus());
                row.createCell(9).setCellValue(m.statusLabel());
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out); return out.toByteArray(); }
        }
    }

    // ===== 解析小工具（同 RosterImportService 模式）=====
    private Sheet firstDataSheet(Workbook wb) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            if (s.getLastRowNum() >= 0 && s.getRow(0) != null) return s;
        }
        throw new BizException(4001, "名单文件为空");
    }
    private Row findHeaderRow(Sheet sheet) {
        int last = Math.min(sheet.getLastRowNum(), 9);
        for (int i = 0; i <= last; i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            for (Cell c : r) {
                String norm = norm(cellStr(c));
                if (norm != null && (norm.contains("姓名") || norm.contains("学号"))) return r;
            }
        }
        throw new BizException(4001, "名单文件缺少表头（需含姓名/学号列）");
    }
    private Map<String, Integer> locateColumns(Row header) {
        Map<String, Integer> col = new HashMap<>();
        for (Cell c : header) {
            String norm = norm(cellStr(c));
            if (norm == null) continue;
            if (!col.containsKey("name") && norm.contains("姓名")) col.put("name", c.getColumnIndex());
            else if (!col.containsKey("studentNo") && norm.contains("学号")) col.put("studentNo", c.getColumnIndex());
            else if (!col.containsKey("dept") && norm.contains("部门")) col.put("dept", c.getColumnIndex());
            else if (!col.containsKey("position") && norm.contains("职位")) col.put("position", c.getColumnIndex());
            else if (!col.containsKey("gender") && norm.contains("性别")) col.put("gender", c.getColumnIndex());
            else if (!col.containsKey("className") && norm.contains("班级")) col.put("className", c.getColumnIndex());
            else if (!col.containsKey("phone") && norm.contains("联系")) col.put("phone", c.getColumnIndex());
            else if (!col.containsKey("political") && norm.contains("政治")) col.put("political", c.getColumnIndex());
        }
        if (!col.containsKey("name")) throw new BizException(4001, "名单文件缺少姓名列");
        return col;
    }
    private Cell optionalCell(Row r, Map<String, Integer> col, String field) {
        Integer idx = col.get(field);
        return idx == null ? null : r.getCell(idx);
    }
    private String cellStr(Cell c) {
        if (c == null) return null;
        switch (c.getCellType()) {
            case STRING: { String v = c.getStringCellValue(); return v == null ? null : v.trim(); }
            case NUMERIC: {
                if (DateUtil.isCellDateFormatted(c)) return new DataFormatter().formatCellValue(c);
                double d = c.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
                return Double.toString(d);
            }
            case BOOLEAN: return Boolean.toString(c.getBooleanCellValue());
            case FORMULA:
                try { String fv = c.getStringCellValue(); return fv == null ? null : fv.trim(); }
                catch (IllegalStateException e) { return Double.toString(c.getNumericCellValue()); }
            default: return null;
        }
    }
    private String norm(String s) { return s == null ? null : s.replaceAll("\\s+", ""); }
    private boolean isBlank(String s) { return s == null || s.isBlank(); }
    private String key(Long sessionId, String studentNo) {
        return sessionId + "|" + (studentNo == null ? "" : studentNo);
    }
}
