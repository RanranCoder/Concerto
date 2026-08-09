package com.pams.module.schedule;

import com.pams.common.BizException;
import com.pams.entity.Department;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.service.NoClassScheduleImportService;
import com.pams.repository.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoClassScheduleImportServiceTest {

    @Test
    void importsFilesAndWritesOutput(@TempDir Path tempDir) throws Exception {
        DepartmentRepository deptRepo = mock(DepartmentRepository.class);
        Department dept = new Department();
        dept.setId(1L);
        dept.setName("文秘部");
        when(deptRepo.findById(1L)).thenReturn(Optional.of(dept));

        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo);
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetable());

        NoClassScheduleImportVO vo = service.importTimetables(List.of(f), 1L, "2025-2026-2", null);

        assertThat(vo.getDeptName()).isEqualTo("文秘部");
        assertThat(vo.getSuccessCount()).isEqualTo(1);
        assertThat(vo.getTotalFiles()).isEqualTo(1);
        assertThat(vo.getFailed()).isEmpty();
        assertThat(vo.getRows()).hasSize(6);
        assertThat(vo.getRows().get(0).getDays().get("1").get(0).getFreeWeeks()).isEqualTo("17-18");
        assertThat(vo.getMarkdown()).contains("张三（17-18）");
        assertThat(vo.getDownloadUrl()).startsWith("无课表/").endsWith(".xlsx");
        assertThat(tempDir.resolve(vo.getDownloadUrl())).exists();
    }

    @Test
    void allFilesFailed_returnsZeroSuccess() {
        NoClassScheduleImportService service = new NoClassScheduleImportService(mock(DepartmentRepository.class));
        MockMultipartFile f = new MockMultipartFile("files", "2025物联网3班.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{});
        NoClassScheduleImportVO vo = service.importTimetables(List.of(f), null, null, null);
        assertThat(vo.getSuccessCount()).isZero();
        assertThat(vo.getFailed()).hasSize(1);
        assertThat(vo.getFailed().get(0).getReason()).contains("姓名");
    }

    @Test
    void resolveDownload_rejectsTraversalAndNullOrBlank() {
        NoClassScheduleImportService service = new NoClassScheduleImportService(mock(DepartmentRepository.class));
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
        NoClassScheduleImportService service = new NoClassScheduleImportService(mock(DepartmentRepository.class));
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetable());

        assertThatThrownBy(() -> service.importTimetables(List.of(f), null, "..\\..\\..\\..\\temp\\pwn", null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(2703));
        assertThat(tempDir.resolve("无课表")).doesNotExist();
    }
}
