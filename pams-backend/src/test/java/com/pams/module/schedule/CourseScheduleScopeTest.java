package com.pams.module.schedule;
import com.pams.common.*;
import com.pams.entity.User;
import com.pams.module.schedule.repository.*;
import com.pams.module.schedule.service.CourseScheduleService;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseScheduleScopeTest {
    @Test void anUnknownPersonCannotBeReportedAsAvailable() {
        var users=mock(UserRepository.class);var schedules=mock(CourseScheduleRepository.class);
        when(users.findById(999L)).thenReturn(Optional.empty());
        var service=new CourseScheduleService(schedules,mock(ScheduleConfigRepository.class),users);
        try {OrgContext.set(1L);assertThatThrownBy(()->service.analyzeFreeTime("S5",List.of(999L))).isInstanceOf(BizException.class);verifyNoInteractions(schedules);}
        finally {OrgContext.clear();}
    }
    @Test void explicitForeignOrganizationIdsAreRejectedBeforeCourseQueries() {
        var users=mock(UserRepository.class);var schedules=mock(CourseScheduleRepository.class);var foreign=new User();foreign.setId(8L);foreign.setOrgId(2L);
        when(users.findById(8L)).thenReturn(Optional.of(foreign));
        var service=new CourseScheduleService(schedules,mock(ScheduleConfigRepository.class),users);
        try {OrgContext.set(1L);assertThatThrownBy(()->service.analyzeFreeTime("S5",List.of(8L))).isInstanceOf(BizException.class);verifyNoInteractions(schedules);}
        finally {OrgContext.clear();}
    }
}
