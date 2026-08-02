package com.pams.module.party;

import com.pams.common.BizException;
import com.pams.module.party.entity.PartyRoster;
import com.pams.module.party.repository.PartyRosterRepository;
import com.pams.module.party.service.RosterImportService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RosterImportServiceTest {

    PartyRosterRepository repo;
    RosterImportService service;

    @BeforeEach
    void setup() {
        repo = mock(PartyRosterRepository.class);
        service = new RosterImportService(repo);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private byte[] buildXlsx(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Sheet1");
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) {
                    r.createCell(j).setCellValue(rows[i][j]);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    /** 模拟简报中的"入党积极分子名单"结构：标题行 + 表头 + 数据。 */
    private byte[] sampleRoster() throws Exception {
        return buildXlsx(new String[][]{
                {"学校党校培训班学员信息表（第40期）"},
                {"单位：xxx    联系人：yyy"},
                {"序号", "学号/工号", "姓 名", "性别", "民族", "班级", "支部", "备注"},
                {"1", "2435101020120", "吴苑", "女", "汉族", "24物联网班", "第一党支部", ""},
                {"2", "2435102020329", "谭子豪", "男", "汉族", "24计算机网络技术3班", "第一党支部", ""},
                {"", "", "", "", "", "", "", ""},
                {"3", "2435102010814", "黄嘉丽", "女", "汉族", "24软件应用技术3班", "第二党支部", ""},
        });
    }

    @Test
    void import_parsesRosterRows() throws Exception {
        int count = service.importFromXlsx(new ByteArrayInputStream(sampleRoster()), "ACTIVE");

        assertThat(count).isEqualTo(3);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(repo).saveAll(captor.capture());
        List<PartyRoster> rows = new ArrayList<>();
        captor.getValue().forEach(e -> rows.add((PartyRoster) e));
        assertThat(rows).hasSize(3);

        PartyRoster first = rows.get(0);
        assertThat(first.getRosterType()).isEqualTo("ACTIVE");
        assertThat(first.getName()).isEqualTo("吴苑");
        assertThat(first.getGender()).isEqualTo("女");
        assertThat(first.getStudentNo()).isEqualTo("2435101020120");
        assertThat(first.getClassName()).isEqualTo("24物联网班");
        assertThat(first.getBranchName()).isEqualTo("第一党支部");

        // 数字单元格学号不出现科学计数法
        assertThat(rows.get(1).getStudentNo()).isEqualTo("2435102020329");
    }

    @Test
    void import_skipsEmptyLines() throws Exception {
        service.importFromXlsx(new ByteArrayInputStream(sampleRoster()), "ACTIVE");
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(repo).saveAll(captor.capture());
        List<PartyRoster> rows = new ArrayList<>();
        captor.getValue().forEach(e -> rows.add((PartyRoster) e));
        // 表中间的空行被跳过
        assertThat(rows).hasSize(3);
    }

    @Test
    void import_missingHeader_throws() throws Exception {
        byte[] bad = buildXlsx(new String[][]{
                {"没有表头的表格"},
                {"a", "b", "c"},
        });
        assertThatThrownBy(() -> service.importFromXlsx(new ByteArrayInputStream(bad), "ACTIVE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("表头");
    }
}
