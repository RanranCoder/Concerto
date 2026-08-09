package com.pams.module.schedule;

import com.pams.module.schedule.controller.CourseScheduleController;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.service.CourseScheduleService;
import com.pams.module.schedule.service.NoClassScheduleImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseScheduleControllerTest {

    @Test
    void importEndpointDelegatesToService() throws Exception {
        NoClassScheduleImportService importService = mock(NoClassScheduleImportService.class);
        CourseScheduleService scheduleService = mock(CourseScheduleService.class);
        CourseScheduleController ctl = new CourseScheduleController(scheduleService, importService);

        NoClassScheduleImportVO vo = new NoClassScheduleImportVO();
        vo.setSuccessCount(1);
        when(importService.importTimetables(anyList(), eq(1L), eq("2025-2026-2"), isNull())).thenReturn(vo);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctl).build();
        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-课表.xlsx", "application/octet-stream", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/course-schedules/import")
                        .file(f)
                        .param("deptId", "1")
                        .param("semester", "2025-2026-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(1));
    }
}
