package com.pams.module.routine;

import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.service.RoutineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
}
