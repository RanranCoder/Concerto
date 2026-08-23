package com.pams.module.routine;

import com.pams.module.routine.controller.ScheduleController;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.entity.SchedulePerson;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.module.routine.service.RoutineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoutineServiceTest {

    /** C3: 排班列表批量加载人员并按下排班归属组装，替代逐条 findByScheduleId 的 N+1 */
    @Test
    void listSchedules_batchLoadsPersonsGroupedBySchedule() {
        ScheduleRepository schedRepo = mock(ScheduleRepository.class);
        SchedulePersonRepository spRepo = mock(SchedulePersonRepository.class);
        RoutineService service = new RoutineService(schedRepo, spRepo, mock(AttendanceRepository.class));

        Schedule s1 = new Schedule(); s1.setId(1L); s1.setScheduleType("SMOKING_CURB");
        Schedule s2 = new Schedule(); s2.setId(2L); s2.setScheduleType("CLASS_DUTY");
        when(schedRepo.findAll()).thenReturn(List.of(s1, s2));

        SchedulePerson p1 = new SchedulePerson(); p1.setScheduleId(1L); p1.setPersonName("张三");
        SchedulePerson p2 = new SchedulePerson(); p2.setScheduleId(1L); p2.setPersonName("李四");
        SchedulePerson p3 = new SchedulePerson(); p3.setScheduleId(2L); p3.setPersonName("王五");
        when(spRepo.findByScheduleIdIn(any())).thenReturn(List.of(p1, p2, p3));

        var list = service.listSchedules("SMOKING_CURB", null, null, null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getId()).isEqualTo(1L);
        assertThat(list.get(0).getPersons())
                .extracting(SchedulePerson::getPersonName)
                .containsExactly("张三", "李四");
        // 只发一次 in 查询，不再逐排班查人员
        verify(spRepo, times(1)).findByScheduleIdIn(any());
        verify(spRepo, never()).findByScheduleId(any());
    }

    /** B6: 导出下载返回后，uploads 目录中的临时 xlsx 已被清理 */
    @Test
    void export_deletesTempFileAfterDownload(@TempDir Path uploadDir) throws Exception {
        ScheduleRepository schedRepo = mock(ScheduleRepository.class);
        SchedulePersonRepository spRepo = mock(SchedulePersonRepository.class);
        RoutineService service = new RoutineService(schedRepo, spRepo, mock(AttendanceRepository.class));
        service.setUploadDir(uploadDir.toString());

        Schedule s = new Schedule();
        s.setId(1L); s.setScheduleType("SMOKING_CURB"); s.setWeekNo(1); s.setWeekday(1);
        s.setSessionName("上午第1-2节"); s.setLocation("一楼大厅");
        when(schedRepo.findAll()).thenReturn(List.of(s));
        SchedulePerson p = new SchedulePerson(); p.setScheduleId(1L); p.setPersonName("张三");
        when(spRepo.findByScheduleIdIn(any())).thenReturn(List.of(p));

        ScheduleController controller = new ScheduleController(service);
        ResponseEntity<byte[]> resp = controller.export(null, null, null, null);

        assertThat(resp.getBody()).isNotEmpty();
        // 响应字节返回后临时文件应已删除
        try (var files = Files.list(uploadDir)) {
            assertThat(files.count()).isZero();
        }
    }
}
