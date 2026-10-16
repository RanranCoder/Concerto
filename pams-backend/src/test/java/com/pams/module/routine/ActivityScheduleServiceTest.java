package com.pams.module.routine;

import com.pams.module.routine.dto.ActivityScheduleSaveRequest;
import com.pams.module.routine.dto.ScheduleRequest;
import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.entity.SchedulePerson;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.module.routine.service.ActivityScheduleService;
import com.pams.module.schedule.service.FreeSlotMatcher;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActivityScheduleServiceTest {
    private final ScheduleRepository schedules = mock(ScheduleRepository.class);
    private final SchedulePersonRepository people = mock(SchedulePersonRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final FreeSlotMatcher matcher = mock(FreeSlotMatcher.class);
    private final List<SchedulePerson> storedPeople = new ArrayList<>();
    private final List<Attendance> storedAttendance = new ArrayList<>();
    private ActivityScheduleService service;
    private Schedule schedule;

    @BeforeEach void setup() {
        service = new ActivityScheduleService(matcher, schedules, people, attendances,
                mock(DepartmentRepository.class), mock(UserRepository.class), mock(ActivityRepository.class));
        schedule = new Schedule(); schedule.setId(1L); schedule.setScheduleType(Schedule.TYPE_ACTIVITY);
        schedule.setStatus(Schedule.STATUS_PUBLISHED); schedule.setSource(Schedule.SOURCE_AUTO);
        schedule.setScheduleDate(LocalDate.of(2026, 10, 14)); schedule.setStartTime(LocalTime.of(14, 0)); schedule.setEndTime(LocalTime.of(15, 40)); schedule.setDeptId(5L);
        when(schedules.findById(1L)).thenReturn(Optional.of(schedule));
        when(schedules.save(any())).thenAnswer(i -> { Schedule s = i.getArgument(0); s.setId(1L); return s; });
        when(people.findByScheduleId(1L)).thenAnswer(i -> new ArrayList<>(storedPeople));
        doAnswer(i -> { storedPeople.clear(); return null; }).when(people).deleteByScheduleId(1L);
        when(people.save(any())).thenAnswer(i -> { SchedulePerson p = i.getArgument(0); storedPeople.add(p); return p; });
        when(attendances.findByScheduleId(1L)).thenAnswer(i -> new ArrayList<>(storedAttendance));
        when(attendances.save(any())).thenAnswer(i -> { Attendance a = i.getArgument(0); if (a.getId() == null) { a.setId((long) storedAttendance.size() + 100); storedAttendance.add(a); } return a; });
        doAnswer(i -> { Long id = i.getArgument(0); storedAttendance.removeIf(a -> id.equals(a.getId())); return null; }).when(attendances).deleteById(anyLong());
    }
    private ScheduleRequest.SchedulePersonItem person(long id, int primary) {
        var p = new ScheduleRequest.SchedulePersonItem(); p.setUserId(id); p.setPersonName("成员" + id); p.setIsPrimary(primary); return p;
    }
    private Attendance attendance(long id, long person, String source, String status) {
        var a = new Attendance(); a.setId(id); a.setScheduleId(1L); a.setPersonId(person); a.setPersonName("成员" + person); a.setStatusSource(source); a.setStatus(status); a.setRemark("历史备注"); storedAttendance.add(a); return a;
    }
    private ActivityScheduleSaveRequest request(List<ScheduleRequest.SchedulePersonItem> persons) {
        var req = new ActivityScheduleSaveRequest(); req.setActivityId(10L); req.setScheduleDate(schedule.getScheduleDate()); req.setStartTime(schedule.getStartTime()); req.setEndTime(schedule.getEndTime()); req.setPersons(persons); return req;
    }
    @Test void manualCreationKeepsExplicitPrimaryAndDeputy() {
        service.createManual(request(List.of(person(2, 0), person(3, 1))));
        assertEquals(List.of(0, 1), storedPeople.stream().map(SchedulePerson::getIsPrimary).toList());
        assertEquals(Schedule.STATUS_DRAFT, ((Schedule) mockingDetails(schedules).getInvocations().stream().filter(i -> i.getMethod().getName().equals("save")).findFirst().orElseThrow().getArgument(0)).getStatus());
    }
    @Test void publishedEditKeepsManualEvidenceAndRetainedSignedResults() {
        Attendance manual = attendance(10, 2, Attendance.SOURCE_MANUAL, Attendance.STATUS_MAKEUP);
        Attendance signed = attendance(11, 3, Attendance.SOURCE_AUTO, Attendance.STATUS_NORMAL);
        attendance(12, 4, Attendance.SOURCE_AUTO, Attendance.STATUS_PENDING);
        service.updateManual(1L, request(List.of(person(3, 1), person(5, 0))));
        assertTrue(storedAttendance.contains(manual), "Removed member's manual evidence must remain addressable by its audit ID");
        assertSame(signed, storedAttendance.stream().filter(a -> a.getPersonId().equals(3L)).findFirst().orElseThrow());
        assertEquals(Attendance.STATUS_NORMAL, signed.getStatus());
        assertEquals("历史备注", manual.getRemark());
        assertFalse(storedAttendance.stream().anyMatch(a -> a.getPersonId().equals(4L)));
        assertEquals(Attendance.STATUS_PENDING, storedAttendance.stream().filter(a -> a.getPersonId().equals(5L)).findFirst().orElseThrow().getStatus());
        verify(attendances, never()).deleteByScheduleId(anyLong());
    }
    @Test void regenerationKeepsManualCorrectionsAndAssignsOnePrimary() {
        Attendance manual = attendance(10, 2, Attendance.SOURCE_MANUAL, Attendance.STATUS_LEAVE);
        when(matcher.matchByPeriod(any(), any(), any(), anyList(), any(), any())).thenReturn(List.of(new FreeSlotMatcher.PeriodSlot(3, "第5-6节", schedule.getStartTime(), schedule.getEndTime(), List.of(new FreeSlotMatcher.Candidate("成员2", 2L), new FreeSlotMatcher.Candidate("成员3", 3L)))));
        assertEquals(2, service.regenerate(1L, null, null, null));
        assertEquals(List.of(1, 0), storedPeople.stream().map(SchedulePerson::getIsPrimary).toList());
        assertSame(manual, storedAttendance.stream().filter(a -> a.getPersonId().equals(2L)).findFirst().orElseThrow());
        assertEquals(Attendance.STATUS_LEAVE, manual.getStatus());
        assertEquals(2, storedAttendance.size());
    }
    @Test void repeatedPublicationDoesNotCreateDuplicateAttendance() {
        SchedulePerson p = new SchedulePerson(); p.setUserId(2L); p.setPersonName("成员2"); storedPeople.add(p);
        service.publish(1L); service.publish(1L);
        assertEquals(1, storedAttendance.size());
        assertEquals(Attendance.STATUS_PENDING, storedAttendance.getFirst().getStatus());
    }
    @Test void sameNameWithDifferentUserIdsKeepsDistinctAttendance() {
        SchedulePerson a = new SchedulePerson(); a.setUserId(2L); a.setPersonName("同名成员");
        SchedulePerson b = new SchedulePerson(); b.setUserId(3L); b.setPersonName("同名成员"); storedPeople.addAll(List.of(a, b));
        service.publish(1L); service.publish(1L);
        assertEquals(2, storedAttendance.size());
    }
}
