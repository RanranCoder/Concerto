package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.dto.SigninFieldConfigRequest;
import com.pams.module.activity.entity.SigninFieldConfig;
import com.pams.module.activity.entity.SigninRoster;
import com.pams.module.activity.repository.SigninFieldConfigRepository;
import com.pams.module.activity.repository.SigninRosterRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.SigninRosterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SigninRosterServiceTest {

    SigninRosterRepository rosterRepo;
    SigninFieldConfigRepository fieldRepo;
    SigninRepository signinRepo;
    ActivityRepository activityRepo;
    SigninRosterService service;

    @BeforeEach
    void setup() {
        rosterRepo = mock(SigninRosterRepository.class);
        fieldRepo = mock(SigninFieldConfigRepository.class);
        signinRepo = mock(SigninRepository.class);
        activityRepo = mock(ActivityRepository.class);
        service = new SigninRosterService(rosterRepo, fieldRepo, signinRepo, activityRepo);
    }

    @Test
    void saveFields_persistsInOrder() {
        var req = List.of(
            new SigninFieldConfigRequest("姓名", "name", true, "TEXT", 1),
            new SigninFieldConfigRequest("学号", "studentNo", false, "TEXT", 2)
        );
        when(activityRepo.existsById(1L)).thenReturn(true);
        when(fieldRepo.save(any(SigninFieldConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        service.saveFields(1L, req);
        verify(fieldRepo, times(2)).save(any(SigninFieldConfig.class));
    }

    @Test
    void summary_countsSignedAndUnsigned() {
        // roster: 2 行（张三/李四），signin: 1 条 name=张三 + studentNo=2025001（与 r1 的姓名+学号全匹配）
        SigninRoster r1 = new SigninRoster(); r1.setId(1L); r1.setActivityId(1L);
        r1.setFieldsJson("{\"姓名\":\"张三\",\"学号\":\"2025001\"}");
        SigninRoster r2 = new SigninRoster(); r2.setId(2L); r2.setActivityId(1L);
        r2.setFieldsJson("{\"姓名\":\"李四\",\"学号\":\"2025002\"}");
        when(rosterRepo.findByActivityId(1L)).thenReturn(List.of(r1, r2));
        // signin 需匹配：注入 signinRepo，返回一条 name=张三、studentNo=2025001 的记录
        var s = new com.pams.module.activity.entity.Signin();
        s.setName("张三");
        s.setStudentNo("2025001");
        when(signinRepo.findByActivityId(1L)).thenReturn(List.of(s));

        var summary = service.summary(1L);
        assertThat(summary.getExpected()).isEqualTo(2);
        assertThat(summary.getSigned()).isEqualTo(1);
        assertThat(summary.getUnsigned()).isEqualTo(1);
    }

    @Test
    void deleteRoster_missing_throws() {
        when(rosterRepo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteRoster(9L)).isInstanceOf(BizException.class);
    }
}
