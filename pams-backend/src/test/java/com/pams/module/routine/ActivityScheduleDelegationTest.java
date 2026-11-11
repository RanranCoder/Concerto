package com.pams.module.routine;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.entity.Department;
import com.pams.entity.User;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.routine.dto.ActivityScheduleGenerateRequest;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.module.routine.service.ActivityScheduleService;
import com.pams.module.schedule.service.FreeSlotMatcher;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ActivityScheduleDelegationTest {
    private final FreeSlotMatcher matcher = mock(FreeSlotMatcher.class);
    private final ScheduleRepository schedules = mock(ScheduleRepository.class);
    private final SchedulePersonRepository people = mock(SchedulePersonRepository.class);
    private final AttendanceRepository attendance = mock(AttendanceRepository.class);
    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ActivityRepository activities = mock(ActivityRepository.class);
    private final ActivityScheduleService service =
            new ActivityScheduleService(matcher, schedules, people, attendance, departments, users, activities);
    private final ActivityScheduleGenerateRequest request = new ActivityScheduleGenerateRequest();

    @BeforeEach
    void setup() {
        OrgContext.set(1L);
        request.setActivityId(123L);
        request.setDeptIds(List.of(77L));
        request.setStartDate(LocalDate.of(2026, 11, 16));
        request.setEndDate(request.getStartDate());
        request.setStartTime(LocalTime.of(9, 0));
        request.setEndTime(LocalTime.of(10, 0));
        request.setPeoplePerSlot(1);

        var activity = new Activity();
        activity.setId(123L);
        activity.setOrgId(1L);
        activity.setStatus(ActivityStatus.EXECUTING);
        when(activities.lockById(123L)).thenReturn(Optional.of(activity));

        var department = new Department();
        department.setId(77L);
        department.setOrgId(1L);
        department.setName("组织部");
        when(departments.findById(77L)).thenReturn(Optional.of(department));
        var user = new User();
        user.setId(9L);
        user.setRealName("张三");
        user.setDept(department);
        user.setOrgId(1L);
        user.setStatus(1);
        when(users.findAllById(anyCollection())).thenReturn(List.of(user));
        slot(request.getStartTime(), request.getEndTime());
    }

    @AfterEach
    void clearTenant() { OrgContext.clear(); }

    @Test
    void directSaveCreatesDraftAndPeopleWithoutAttendance() {
        when(schedules.save(any(Schedule.class))).thenAnswer(call -> {
            Schedule saved = call.getArgument(0);
            assertThat(saved.getStatus()).isEqualTo(Schedule.STATUS_DRAFT);
            assertThat(saved.getActivityId()).isEqualTo(123L);
            assertThat(saved.getDeptId()).isEqualTo(77L);
            saved.setId(88L);
            return saved;
        });
        var result = service.generateDelegatedDraft(request, fingerprint());
        assertThat(result).hasSize(1);
        verify(people).save(any());
        verifyNoInteractions(attendance);
    }

    @Test
    void previewFingerprintCannotAuthorizeAClassPeriodOutsideTheRequestedTime() {
        slot(LocalTime.of(8, 45), LocalTime.of(9, 30));
        assertThatThrownBy(() -> service.generateDelegatedDraft(request, fingerprint()))
                .isInstanceOf(BizException.class).hasMessageContaining("超出授权时段");
        verify(schedules, never()).save(any());
    }

    @Test
    void overlappingDraftPreventsDuplicateGeneration() {
        var existing = new Schedule();
        existing.setStartTime(LocalTime.of(9, 15));
        existing.setEndTime(LocalTime.of(9, 45));
        when(schedules.findByScheduleTypeAndActivityIdAndScheduleDateBetween(Schedule.TYPE_ACTIVITY,
                123L, request.getStartDate(), request.getEndDate())).thenReturn(List.of(existing));
        assertThatThrownBy(() -> service.generateDelegatedDraft(request, fingerprint()))
                .isInstanceOf(BizException.class).hasMessageContaining("已有重叠排班");
        verify(schedules, never()).save(any());
    }

    private String fingerprint() {
        return ActivityScheduleService.previewFingerprint(service.preview(request));
    }

    private void slot(LocalTime start, LocalTime end) {
        var slot = new FreeSlotMatcher.PeriodSlot(1, "第1节", start, end,
                List.of(new FreeSlotMatcher.Candidate("张三", 9L)));
        when(matcher.matchByPeriod(request.getStartDate(), request.getStartTime(), request.getEndTime(),
                request.getDeptIds(), null, null)).thenReturn(List.of(slot));
    }
}
