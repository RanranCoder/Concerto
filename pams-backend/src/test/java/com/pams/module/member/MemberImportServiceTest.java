package com.pams.module.member;

import com.pams.entity.Department;
import com.pams.module.member.dto.MemberImportResultVO;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.service.MemberImportService;
import com.pams.repository.DepartmentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberImportServiceTest {

    MemberRepository memberRepo;
    DepartmentRepository deptRepo;
    MemberImportService service;

    @BeforeEach
    void setup() {
        memberRepo = mock(MemberRepository.class);
        deptRepo = mock(DepartmentRepository.class);
        service = new MemberImportService(memberRepo, deptRepo);
        Department wm = new Department(); wm.setId(2L); wm.setName("文秘部");
        Department zzb = new Department(); zzb.setId(3L); zzb.setName("组织部");
        when(deptRepo.findAll()).thenReturn(List.of(wm, zzb));
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of());
    }

    /** 构造：标题行 + 表头 + 数据（部门列用合并单元格：第2行部门=文秘部，第3行部门留空） */
    private byte[] rosterXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Sheet1");
            String[][] rows = {
                {"第九届信息工程学院党建办公室干部干事信息登记表"},
                {"序号", "部门", "职位", "姓名", "性别", "学号", "班级", "联系方式", "政治面貌"},
                {"1", "文秘部", "部长", "吴苑", "女", "2435101020120", "24物联网班", "15907536461", "共青团员"},
                {"2", "", "干事", "谢文杰", "男", "2535102010537", "25计应5班", "13556493207", "群众"},
                {"3", "组织部", "干事", "蔡键泽", "男", "2535102030201", "25软件技术2班", "15219326575", "共青团员"},
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) r.createCell(j).setCellValue(rows[i][j]);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out); return out.toByteArray(); }
        }
    }

    @Test
    void import_parsesAndForwardFillsDept() throws Exception {
        var r = service.importFromXlsx(new ByteArrayInputStream(rosterXlsx()), 1L);
        assertThat(r.success()).isEqualTo(3);
        assertThat(r.skipped()).isZero();
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(memberRepo).saveAll(captor.capture());
        var saved = new java.util.ArrayList<com.pams.module.member.entity.Member>();
        captor.getValue().forEach(e -> saved.add((com.pams.module.member.entity.Member) e));
        // 谢文杰部门为空，前向填充为 文秘部
        var xie = saved.stream().filter(m -> m.getName().equals("谢文杰")).findFirst().orElseThrow();
        assertThat(xie.getDeptId()).isEqualTo(2L);
        assertThat(xie.getPosition()).isEqualTo("STAFF");
        assertThat(xie.getStatus()).isEqualTo("ACTIVE");
        var cai = saved.stream().filter(m -> m.getName().equals("蔡键泽")).findFirst().orElseThrow();
        assertThat(cai.getDeptId()).isEqualTo(3L);
        assertThat(cai.getPoliticalStatus()).isEqualTo("共青团员");
    }

    /** 库中已有同届同学号成员时，导入该行记失败（学号已存在），不重复写入。 */
    @Test
    void import_skipsDuplicateFromDb() throws Exception {
        com.pams.module.member.entity.Member existing = new com.pams.module.member.entity.Member();
        existing.setStudentNo("2435101020120"); existing.setName("吴苑");
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(existing));

        var r = service.importFromXlsx(new ByteArrayInputStream(rosterXlsx()), 1L);

        assertThat(r.success()).isEqualTo(2);
        assertThat(r.failed()).hasSize(1);
        assertThat(r.failed().get(0).reason()).contains("学号已存在");
    }

    @Test
    void import_unknownPosition_reportsFailureRow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("S");
            sheet.createRow(0).createCell(0).setCellValue("姓名");
            sheet.createRow(1).createCell(0).setCellValue("张三");
            // 只有姓名列，职位列缺失 → 职位无法识别
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out);
                var r = service.importFromXlsx(new ByteArrayInputStream(out.toByteArray()), 1L);
                assertThat(r.failed()).hasSize(1);
                assertThat(r.failed().get(0).reason()).contains("职位");
            }
        }
    }

    @Test
    void import_blankNameSkipsRow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("S");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("姓名"); h.createCell(1).setCellValue("学号"); h.createCell(2).setCellValue("职位");
            sheet.createRow(1).createCell(0).setCellValue("");            // 空行：姓名+学号皆空 → 跳过
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("张三"); r2.createCell(1).setCellValue("20250999"); r2.createCell(2).setCellValue("干事");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out);
                var r = service.importFromXlsx(new ByteArrayInputStream(out.toByteArray()), 1L);
                assertThat(r.success()).isEqualTo(1);
                assertThat(r.failed()).isEmpty();
            }
        }
    }
}
