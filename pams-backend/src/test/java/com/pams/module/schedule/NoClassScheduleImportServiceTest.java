package com.pams.module.schedule;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.entity.Department;
import com.pams.module.schedule.dto.NoClassScheduleGeneratedVO;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.entity.NoClassScheduleRecord;
import com.pams.module.schedule.repository.NoClassScheduleRecordRepository;
import com.pams.module.schedule.service.NoClassScheduleImportService;
import com.pams.repository.DepartmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NoClassScheduleImportServiceTest {

    private NoClassScheduleRecordRepository recordRepo;
    private DepartmentRepository deptRepo;

    @BeforeEach
    void setUp() {
        recordRepo = mock(NoClassScheduleRecordRepository.class);
        deptRepo = mock(DepartmentRepository.class);
        Department dept = new Department();
        dept.setId(1L);
        dept.setOrgId(OrgContext.DEFAULT_ORG_ID);
        dept.setName("文秘部");
        when(deptRepo.findById(1L)).thenReturn(Optional.of(dept));
    }

    @Test
    void importsFilesAndWritesOutput(@TempDir Path tempDir) throws Exception {
        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo, recordRepo);
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetable());

        NoClassScheduleImportVO vo = service.importTimetables(List.of(f), 1L, "2025-2026-2", null);

        assertThat(vo.getDeptName()).isEqualTo("文秘部");
        assertThat(vo.getDeptId()).isEqualTo(1L);
        assertThat(vo.isPublished()).isTrue();
        assertThat(vo.getSuccessCount()).isEqualTo(1);
        assertThat(vo.getTotalFiles()).isEqualTo(1);
        assertThat(vo.getFailed()).isEmpty();
        assertThat(vo.getRows()).hasSize(6);
        assertThat(vo.getRows().get(0).getDays().get("1").get(0).getFreeWeeks()).isEqualTo("17-18");
        assertThat(vo.getMarkdown()).contains("张三（17-18）");
        assertThat(vo.getDownloadUrl()).startsWith("无课表/").endsWith(".xlsx");
        assertThat(tempDir.resolve(vo.getDownloadUrl())).exists();
        assertThat(service.resolveDownload(vo.getDownloadUrl())).isEqualTo(tempDir.resolve(vo.getDownloadUrl()));
        try {
            OrgContext.set(2L);
            assertThatThrownBy(() -> service.resolveDownload(vo.getDownloadUrl())).isInstanceOf(BizException.class);
        } finally {
            OrgContext.clear();
        }
    }

    @Test
    void import_respectsMaxWeekBeyond18(@TempDir Path tempDir) throws Exception {
        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo, recordRepo);
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetableWithCourse("课程A(1-22)[1,2]◇1101◇老师甲"));

        // maxWeek=25：课程占 1-22 周，空闲应为 23-25；若解析仍按 18 截断会误报 19-25
        NoClassScheduleImportVO vo = service.importTimetables(List.of(f), 1L, "2025-2026-2", 25);
        assertThat(vo.getRows().get(0).getDays().get("1").get(0).getFreeWeeks()).isEqualTo("23-25");
    }

    @Test
    void allFilesFailed_returnsZeroSuccess() {
        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo, recordRepo);
        MockMultipartFile f = new MockMultipartFile("files", "2025物联网3班.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{});
        NoClassScheduleImportVO vo = service.importTimetables(List.of(f), 1L, "2025-2026-2", null);
        assertThat(vo.getSuccessCount()).isZero();
        assertThat(vo.isPublished()).isFalse();
        assertThat(vo.getFailed()).hasSize(1);
        assertThat(vo.getFailed().get(0).getReason()).contains("姓名");
        verifyNoInteractions(recordRepo);
    }

    @Test
    void resolveDownload_rejectsTraversalAndNullOrBlank() {
        NoClassScheduleImportService service = new NoClassScheduleImportService(mock(DepartmentRepository.class), recordRepo);
        assertThatThrownBy(() -> service.resolveDownload("../escape"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(2705));
        assertThatThrownBy(() -> service.resolveDownload("..\\..\\escape"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(2705));
        assertThatThrownBy(() -> service.resolveDownload(null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(2705));
        assertThatThrownBy(() -> service.resolveDownload("   "))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(2705));
    }

    @Test
    void malformedSemester_rejectedBeforeAnyWrite(@TempDir Path tempDir) throws Exception {
        NoClassScheduleImportService service = new NoClassScheduleImportService(mock(DepartmentRepository.class), recordRepo);
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetable());

        assertThatThrownBy(() -> service.importTimetables(List.of(f), null, "..\\..\\..\\..\\temp\\pwn", null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(2703));
        assertThat(tempDir.resolve("无课表")).doesNotExist();
    }

    @Test
    void importPersistsGeneratedGrid(@TempDir Path tempDir) throws Exception {
        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo, recordRepo);
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetable());
        service.importTimetables(List.of(f), 1L, "2025-2026-2", null);

        verify(recordRepo).saveLatest(eq(1L), eq(1L), eq("文秘部"), eq("2025-2026-2"), any(), any());
    }

    @Test
    void importWithIncompleteScope_rejectsBeforeGenerating(@TempDir Path tempDir) throws Exception {
        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo, recordRepo);
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetable());

        assertThatThrownBy(() -> service.importTimetables(List.of(f), null, "2025-2026-2", null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(2703));
        assertThatThrownBy(() -> service.importTimetables(List.of(f), 1L, " ", null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("学期"));
        try {
            OrgContext.set(2L);
            assertThatThrownBy(() -> service.importTimetables(List.of(f), 1L, "2025-2026-2", null))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("当前组织"));
        } finally {
            OrgContext.clear();
        }
        assertThat(tempDir.resolve("无课表")).doesNotExist();
        verifyNoInteractions(recordRepo);
    }

    @Test
    void partialImportReturnsPreviewWithoutUpdatingPublishedRecord(@TempDir Path tempDir) throws Exception {
        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo, recordRepo);
        service.setUploadDir(tempDir.toString());
        MockMultipartFile valid = new MockMultipartFile("files", "张三-课表.xlsx", null,
                ClassTimetableParserTest.buildTimetable());
        MockMultipartFile invalid = new MockMultipartFile("files", "张三-课表.xlsx", null, new byte[0]);

        var result = service.importTimetables(List.of(valid, invalid), 1L, "2025-2026-2", null);

        assertThat(result.isPublished()).isFalse();
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRows()).isNotEmpty();
        assertThat(result.getFailed()).singleElement().satisfies(failure -> {
            assertThat(failure.getFileIndex()).isEqualTo(1);
            assertThat(failure.getReason()).contains("文件为空");
        });
        verifyNoInteractions(recordRepo);
        assertThat(service.importTimetables(List.of(valid), 1L, "2025-2026-2", null).isPublished()).isTrue();
        verify(recordRepo).saveLatest(eq(1L), eq(1L), eq("文秘部"), eq("2025-2026-2"), any(), any());
    }

    @Test
    void getGenerated_returnsStoredRows(@TempDir Path tempDir) throws Exception {
        // 预置一条记录
        NoClassScheduleRecord rec = new NoClassScheduleRecord();
        rec.setId(1L); rec.setDeptId(1L); rec.setDeptName("文秘部"); rec.setSemester("2025-2026-2");
        rec.setGridJson("[{\"period\":1,\"label\":\"第一二节\",\"halfDay\":\"上午\",\"days\":{\"1\":[{\"name\":\"张三\",\"freeWeeks\":\"17-18\"}]}}]");
        rec.setCreatedAt(java.time.LocalDateTime.now());
        when(recordRepo.findByDeptIdAndSemester(1L, "2025-2026-2")).thenReturn(Optional.of(rec));

        NoClassScheduleImportService service = new NoClassScheduleImportService(mock(DepartmentRepository.class), recordRepo);
        NoClassScheduleGeneratedVO vo = service.getGenerated(1L, "2025-2026-2");
        assertThat(vo.getDeptName()).isEqualTo("文秘部");
        assertThat(vo.getRows()).hasSize(1);
        assertThat(vo.getRows().get(0).getDays().get("1").get(0).getName()).isEqualTo("张三");

        // 无记录返回 null
        when(recordRepo.findByDeptIdAndSemester(2L, "2025-2026-2")).thenReturn(Optional.empty());
        assertThat(service.getGenerated(2L, "2025-2026-2")).isNull();
        // semester 为空返回 null
        assertThat(service.getGenerated(1L, "  ")).isNull();
    }
}
