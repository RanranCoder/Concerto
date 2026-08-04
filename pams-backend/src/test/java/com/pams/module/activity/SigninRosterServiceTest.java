package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.dto.SigninFieldConfigRequest;
import com.pams.module.activity.entity.SigninFieldConfig;
import com.pams.module.activity.entity.SigninRoster;
import com.pams.module.activity.repository.SigninFieldConfigRepository;
import com.pams.module.activity.repository.SigninRosterRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.SigninRosterService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SigninRosterServiceTest {

    SigninRosterRepository rosterRepo;
    SigninFieldConfigRepository fieldRepo;
    SigninRepository signinRepo;
    ActivityRepository activityRepo;
    SigninRosterService service;

    @BeforeEach
    void setup() {
        rosterRepo = mock(SigninRosterRepository.class);
        fieldRepo = mock(SigninFieldConfigRepository.class);
        signinRepo = mock(SigninRepository.class);
        activityRepo = mock(ActivityRepository.class);
        service = new SigninRosterService(rosterRepo, fieldRepo, signinRepo, activityRepo);
    }

    @Test
    void saveFields_persistsInOrder() {
        var req = List.of(
            new SigninFieldConfigRequest("姓名", "name", true, "TEXT", 1),
            new SigninFieldConfigRequest("学号", "studentNo", false, "TEXT", 2)
        );
        when(activityRepo.existsById(1L)).thenReturn(true);
        when(fieldRepo.save(any(SigninFieldConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        service.saveFields(1L, req);
        verify(fieldRepo, times(2)).save(any(SigninFieldConfig.class));
    }

    @Test
    void summary_countsSignedAndUnsigned() {
        // roster: 2 行（张三/李四），signin: 1 条 name=张三 + studentNo=2025001（与 r1 的姓名+学号全匹配）
        SigninRoster r1 = new SigninRoster(); r1.setId(1L); r1.setActivityId(1L);
        r1.setFieldsJson("{\"姓名\":\"张三\",\"学号\":\"2025001\"}");
        SigninRoster r2 = new SigninRoster(); r2.setId(2L); r2.setActivityId(1L);
        r2.setFieldsJson("{\"姓名\":\"李四\",\"学号\":\"2025002\"}");
        when(rosterRepo.findByActivityId(1L)).thenReturn(List.of(r1, r2));
        // signin 需匹配：注入 signinRepo，返回一条 name=张三、studentNo=2025001 的记录
        var s = new com.pams.module.activity.entity.Signin();
        s.setName("张三");
        s.setStudentNo("2025001");
        when(signinRepo.findByActivityId(1L)).thenReturn(List.of(s));

        var summary = service.summary(1L);
        assertThat(summary.getExpected()).isEqualTo(2);
        assertThat(summary.getSigned()).isEqualTo(1);
        assertThat(summary.getUnsigned()).isEqualTo(1);
    }

    @Test
    void deleteRoster_missing_throws() {
        when(rosterRepo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteRoster(9L)).isInstanceOf(BizException.class);
    }

    // ==================== Excel 上传边界（Task5 联调回归） ====================

    /** 构造内存 xlsx：每行数组 = 一行的单元格文本（用于表头/数据） */
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

    private MockMultipartFile rosterFile(byte[] bytes) {
        return new MockMultipartFile("file", "名单.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private void stubFields(String... names) {
        when(fieldRepo.findByActivityIdOrderBySortOrderAsc(1L)).thenReturn(
                java.util.Arrays.stream(names)
                        .map(n -> {
                            SigninFieldConfig c = new SigninFieldConfig();
                            c.setFieldName(n);
                            c.setRequired(1);
                            c.setFieldType("TEXT");
                            return c;
                        })
                        .toList());
    }

    /** 同一文件内重复行（姓名+学号相同）只入库一次：4 数据行去重后 added=2。 */
    @Test
    void upload_duplicateRowWithinFile_deduplicates() throws Exception {
        when(activityRepo.existsById(1L)).thenReturn(true);
        stubFields("姓名", "学号");
        byte[] xlsx = buildXlsx(new String[][]{
                {"姓名", "学号"},
                {"张三", "2025001"},
                {"张三", "2025001"},
                {"李四", "2025002"},
        });
        when(rosterRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Integer> res = service.uploadFromXlsx(1L, rosterFile(xlsx));

        assertThat(res.get("added")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(rosterRepo).saveAll(captor.capture());
        long saved = java.util.stream.StreamSupport.stream(captor.getValue().spliterator(), false).count();
        assertThat(saved).isEqualTo(2);
    }

    /** 必填字段在表头缺失 → 2402 明确报错（而不是静默入库空值）。 */
    @Test
    void upload_missingRequiredColumn_throws2402() throws Exception {
        when(activityRepo.existsById(1L)).thenReturn(true);
        stubFields("姓名", "学号");
        byte[] xlsx = buildXlsx(new String[][]{
                {"姓名"}, // 缺「学号」必填列
                {"张三"},
        });

        assertThatThrownBy(() -> service.uploadFromXlsx(1L, rosterFile(xlsx)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(2402));
        verify(rosterRepo, never()).saveAll(any());
    }

    /** 表头连「姓名」都没有（纯学号名单）→ 2405 明确报错。 */
    @Test
    void upload_missingNameHeader_throws2405() throws Exception {
        when(activityRepo.existsById(1L)).thenReturn(true);
        stubFields("姓名", "学号");
        byte[] xlsx = buildXlsx(new String[][]{
                {"学号"},
                {"2025001"},
        });

        assertThatThrownBy(() -> service.uploadFromXlsx(1L, rosterFile(xlsx)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(2405));
        verify(rosterRepo, never()).saveAll(any());
    }

    /** 空名单（只有表头无数据行）→ added=0，不报错。 */
    @Test
    void upload_emptyRoster_returnsZero() throws Exception {
        when(activityRepo.existsById(1L)).thenReturn(true);
        stubFields("姓名", "学号");
        byte[] xlsx = buildXlsx(new String[][]{
                {"姓名", "学号"},
        });
        when(rosterRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Integer> res = service.uploadFromXlsx(1L, rosterFile(xlsx));

        assertThat(res.get("added")).isZero();
    }

    /** 重名场景：姓名相同但学号不同的两行，应视为两个应签人（互不干扰）。 */
    @Test
    void summary_twoPeopleSameName_distinguishedByStudentNo() throws Exception {
        SigninRoster r1 = new SigninRoster(); r1.setId(1L); r1.setActivityId(1L);
        r1.setFieldsJson("{\"姓名\":\"张三\",\"学号\":\"2025001\"}");
        SigninRoster r2 = new SigninRoster(); r2.setId(2L); r2.setActivityId(1L);
        r2.setFieldsJson("{\"姓名\":\"张三\",\"学号\":\"2025002\"}");
        when(rosterRepo.findByActivityId(1L)).thenReturn(List.of(r1, r2));
        // 只签到了学号 2025001 的张三 → 仅 r1 已签
        var s = new com.pams.module.activity.entity.Signin();
        s.setName("张三");
        s.setStudentNo("2025001");
        when(signinRepo.findByActivityId(1L)).thenReturn(List.of(s));

        var summary = service.summary(1L);
        assertThat(summary.getExpected()).isEqualTo(2);
        assertThat(summary.getSigned()).isEqualTo(1);
        assertThat(summary.getUnsigned()).isEqualTo(1);
    }
}
