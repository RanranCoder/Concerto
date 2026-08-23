package com.pams.module.routine;

import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.module.routine.service.RoutineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttendanceServiceTest {

    AttendanceRepository repo;
    RoutineService service;

    @BeforeEach
    void setup() {
        repo = mock(AttendanceRepository.class);
        service = new RoutineService(repo);
    }

    @Test
    void summary_countsStatuses() {
        Attendance a = new Attendance();
        a.setPersonName("张三"); a.setStatus("PRESENT");
        Attendance b = new Attendance();
        b.setPersonName("张三"); b.setStatus("LEAVE");
        Attendance c = new Attendance();
        c.setPersonName("李四"); c.setStatus("ABSENT");
        when(repo.findAll()).thenReturn(List.of(a, b, c));

        var summary = service.summary(null, null);
        assertThat(summary).hasSize(2);
        Map<String, Object> zhangsan = summary.stream()
                .filter(m -> "张三".equals(m.get("personName")))
                .findFirst().orElseThrow();
        assertThat(zhangsan.get("shouldAttend")).isEqualTo(2);
        assertThat(zhangsan.get("present")).isEqualTo(1);
        assertThat(zhangsan.get("leave")).isEqualTo(1);
        assertThat(zhangsan.get("absent")).isEqualTo(0);
        assertThat(zhangsan.get("count")).isEqualTo(2);

        Map<String, Object> lisi = summary.stream()
                .filter(m -> "李四".equals(m.get("personName")))
                .findFirst().orElseThrow();
        assertThat(lisi.get("shouldAttend")).isEqualTo(1);
        assertThat(lisi.get("absent")).isEqualTo(1);
        assertThat(lisi.get("present")).isEqualTo(0);
        assertThat(lisi.get("leave")).isEqualTo(0);
        assertThat(lisi.get("count")).isEqualTo(1);
    }

    /** type 语义：按考勤所属排班的 scheduleType 过滤（如"本周禁烟值班考勤汇总"），而非考勤状态 */
    @Test
    void summary_typeFiltersByScheduleType() {
        ScheduleRepository schedRepo = mock(ScheduleRepository.class);
        RoutineService svc = new RoutineService(schedRepo, repo);

        Schedule smoking = new Schedule();
        smoking.setId(1L); smoking.setScheduleType("SMOKING_CURB"); smoking.setWeekNo(3);
        Schedule duty = new Schedule();
        duty.setId(2L); duty.setScheduleType("CLASS_DUTY"); duty.setWeekNo(3);
        Schedule noWeek = new Schedule();
        noWeek.setId(3L); noWeek.setScheduleType("SMOKING_CURB"); noWeek.setWeekNo(null);

        Attendance a = new Attendance();
        a.setPersonName("张三"); a.setStatus("PRESENT"); a.setScheduleId(1L);
        Attendance b = new Attendance();
        b.setPersonName("李四"); b.setStatus("PRESENT"); b.setScheduleId(2L);
        Attendance c = new Attendance();
        c.setPersonName("王五"); c.setStatus("PRESENT"); c.setScheduleId(3L);
        when(repo.findAll()).thenReturn(List.of(a, b, c));
        // C3: 批量加载排班（findAllById），替代逐条 findById 的 N+1
        when(schedRepo.findAllById(any())).thenReturn(List.of(smoking, duty, noWeek));

        var all = svc.summary(null, null);
        assertThat(all).extracting(m -> m.get("personName")).containsExactlyInAnyOrder("张三", "李四", "王五");

        var byType = svc.summary(null, "SMOKING_CURB");
        assertThat(byType).extracting(m -> m.get("personName")).containsExactly("张三", "王五");

        var byTypeAndWeek = svc.summary(3, "SMOKING_CURB");
        assertThat(byTypeAndWeek).extracting(m -> m.get("personName")).containsExactly("张三");

        var noMatch = svc.summary(null, "ARCHIVE");
        assertThat(noMatch).isEmpty();

        var blank = svc.summary(null, "");
        assertThat(blank).hasSize(3);
    }

    /** 未关联排班的考勤不匹配任何 weekNo/type 过滤；空串/空白 type 视为不限制 */
    @Test
    void summary_unlinkedAttendanceMatchesNoFilter() {
        ScheduleRepository schedRepo = mock(ScheduleRepository.class);
        RoutineService svc = new RoutineService(schedRepo, repo);

        Schedule s = new Schedule();
        s.setId(10L); s.setScheduleType("SMOKING_CURB"); s.setWeekNo(2);

        Attendance linked = new Attendance();
        linked.setPersonName("张三"); linked.setStatus("PRESENT"); linked.setScheduleId(10L);
        Attendance unlinked = new Attendance();
        unlinked.setPersonName("李四"); unlinked.setStatus("PRESENT"); unlinked.setScheduleId(null);
        when(repo.findAll()).thenReturn(List.of(linked, unlinked));
        when(schedRepo.findAllById(any())).thenReturn(List.of(s));

        assertThat(svc.summary(2, null)).extracting(m -> m.get("personName")).containsExactly("张三");
        assertThat(svc.summary(null, "SMOKING_CURB")).extracting(m -> m.get("personName")).containsExactly("张三");

        assertThat(svc.summary(null, "   ")).hasSize(2);
        assertThat(svc.summary(null, null)).hasSize(2);
    }

    /** C3: 汇总按 weekNo/type 过滤时批量加载排班（一次 findAllById），而非逐条 findById 的 N+1 */
    @Test
    void summary_batchLoadsSchedulesInsteadOfPerRowFindById() {
        ScheduleRepository schedRepo = mock(ScheduleRepository.class);
        RoutineService svc = new RoutineService(schedRepo, repo);

        Schedule s = new Schedule();
        s.setId(7L); s.setScheduleType("SMOKING_CURB"); s.setWeekNo(3);
        // 3 条考勤同属一个排班，仅需一次批量加载
        Attendance a1 = new Attendance();
        a1.setPersonName("张三"); a1.setStatus("PRESENT"); a1.setScheduleId(7L);
        Attendance a2 = new Attendance();
        a2.setPersonName("李四"); a2.setStatus("PRESENT"); a2.setScheduleId(7L);
        Attendance a3 = new Attendance();
        a3.setPersonName("王五"); a3.setStatus("LEAVE"); a3.setScheduleId(7L);
        when(repo.findAll()).thenReturn(List.of(a1, a2, a3));
        when(schedRepo.findAllById(any())).thenReturn(List.of(s));

        var byType = svc.summary(null, "SMOKING_CURB");
        assertThat(byType).hasSize(3);
        verify(schedRepo, times(1)).findAllById(any());
        verify(schedRepo, never()).findById(any());
        // 未命中过滤的 type 同样只触发一次批量加载
        assertThat(svc.summary(null, "ARCHIVE")).isEmpty();
        verify(schedRepo, times(2)).findAllById(any());
    }
}
