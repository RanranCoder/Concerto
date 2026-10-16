package com.pams.module.routine;

import com.pams.common.BizException;
import com.pams.module.routine.dto.ActivityScheduleGenerateRequest;
import com.pams.module.routine.repository.*;
import com.pams.module.routine.service.ActivityScheduleService;
import com.pams.module.schedule.service.FreeSlotMatcher;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ActivitySchedulePreviewTest {
    final FreeSlotMatcher matcher = mock(FreeSlotMatcher.class);
    final ScheduleRepository schedules = mock(ScheduleRepository.class);
    final SchedulePersonRepository people = mock(SchedulePersonRepository.class);
    final AttendanceRepository attendance = mock(AttendanceRepository.class);
    final ActivityRepository activities = mock(ActivityRepository.class);
    final ActivityScheduleService service = new ActivityScheduleService(matcher, schedules, people, attendance,
            mock(DepartmentRepository.class), mock(UserRepository.class), activities);

    ActivityScheduleGenerateRequest request() {
        var req = new ActivityScheduleGenerateRequest();
        req.setActivityId(10L); req.setStartDate(LocalDate.of(2026,10,15)); req.setEndDate(LocalDate.of(2026,10,16));
        req.setStartTime(LocalTime.of(14,0)); req.setEndTime(LocalTime.of(15,40)); req.setDeptIds(List.of(1L)); req.setPeoplePerSlot(1);
        return req;
    }
    void candidates(String... names) {
        when(matcher.matchByPeriod(any(), any(), any(), anyList(), any(), any())).thenReturn(List.of(
                new FreeSlotMatcher.PeriodSlot(3,"第5–6节",LocalTime.of(14,0),LocalTime.of(15,40),
                        java.util.Arrays.stream(names).map(n -> new FreeSlotMatcher.Candidate(n,1L)).toList())));
    }
    @Test void previewIsReadOnlyAndBalancesPeopleAcrossDates() {
        candidates("乙同学","甲同学");
        List<Map<String,Object>> details = service.preview(request());
        assertThat(details).hasSize(2);
        assertThat(details.get(0).get("persons").toString()).contains("乙同学");
        assertThat(details.get(1).get("persons").toString()).contains("甲同学");
        verifyNoInteractions(schedules,people,attendance,activities);
    }
    @Test void noCandidateSlotsKeepTheirMissingCountAndDoNotWriteAnything() {
        candidates();
        var details = service.preview(request());
        assertThat(details.get(0).get("count")).isEqualTo(0);
        var slot = (Map<?,?>)((List<?>)details.get(0).get("slots")).getFirst();
        assertThat(slot.get("missingCount")).isEqualTo(1);
        verifyNoInteractions(schedules,people,attendance,activities);
    }
    @Test void changedCandidateSetBlocksCommitBeforeAnyWrites() {
        candidates("甲同学");
        String fingerprint = ActivityScheduleService.previewFingerprint(service.preview(request()));
        candidates("乙同学");
        assertThatThrownBy(() -> service.generateReviewed(request(),fingerprint)).isInstanceOf(BizException.class)
                .hasMessageContaining("候选人员已变化");
        verifyNoInteractions(schedules,people,attendance,activities);
    }
    @Test void matchingPreviewWritesDraftsWithTheReviewedPeople() {
        candidates("甲同学");
        when(schedules.save(any())).thenAnswer(i -> {var s=(com.pams.module.routine.entity.Schedule)i.getArgument(0);s.setId(1L);return s;});
        var preview = service.preview(request());
        assertThat(service.generateReviewed(request(),ActivityScheduleService.previewFingerprint(preview))).isEqualTo(preview);
        verify(schedules,times(2)).save(argThat(s -> "DRAFT".equals(s.getStatus())));
        verify(people,times(2)).save(argThat(p -> "甲同学".equals(p.getPersonName())));
        verifyNoInteractions(attendance);
    }
}
