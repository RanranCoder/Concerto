package com.pams.module.activity;

import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.entity.SigninRoster;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.SigninFieldConfigRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.repository.SigninRosterRepository;
import com.pams.module.activity.service.SigninRosterService;
import com.pams.module.activity.service.SigninService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * scan 应签名单宽松匹配（SigninService.scanSignin 4 参重载）：
 * 命中名单 → remark 追加"（应签名单）"；不命中 → 仍签到且 remark 无名单标记。
 * 后端应用装配 SigninRosterService，测试注入 mock 验证标记逻辑。
 */
class SigninScanRosterTest {

    SigninRepository signinRepo;
    ActivityRepository activityRepo;
    SigninRosterRepository rosterRepo;
    SigninFieldConfigRepository fieldRepo;
    SigninRosterService rosterService;
    SigninService service;

    @BeforeEach
    void setup() {
        signinRepo = mock(SigninRepository.class);
        activityRepo = mock(ActivityRepository.class);
        rosterRepo = mock(SigninRosterRepository.class);
        fieldRepo = mock(SigninFieldConfigRepository.class);
        rosterService = new SigninRosterService(rosterRepo, fieldRepo, signinRepo, activityRepo);
        service = new SigninService(signinRepo, activityRepo, rosterService);
        when(signinRepo.save(any(Signin.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SigninRoster rosterOf(String json) {
        SigninRoster r = new SigninRoster();
        r.setId(1L);
        r.setActivityId(1L);
        r.setFieldsJson(json);
        return r;
    }

    @Test
    void scan_fieldsMatchRoster_marksRemark() {
        when(activityRepo.existsById(1L)).thenReturn(true);
        when(rosterRepo.findByActivityId(1L)).thenReturn(
                List.of(rosterOf("{\"姓名\":\"张三\",\"学号\":\"2025001\"}")));
        var t = service.generateToken(1L);
        Signin s = service.scanSignin(t.getToken(), "张三", "2025001",
                Map.of("姓名", "张三", "学号", "2025001"));
        assertThat(s.getSignType()).isEqualTo(Signin.SignType.SCAN);
        assertThat(s.getRemark()).contains("应签名单");
    }

    @Test
    void scan_fieldsNotInRoster_stillSignsWithoutMark() {
        when(activityRepo.existsById(1L)).thenReturn(true);
        when(rosterRepo.findByActivityId(1L)).thenReturn(
                List.of(rosterOf("{\"姓名\":\"张三\",\"学号\":\"2025001\"}")));
        var t = service.generateToken(1L);
        Signin s = service.scanSignin(t.getToken(), "王五", "9999999",
                Map.of("姓名", "王五", "学号", "9999999"));
        assertThat(s.getSignType()).isEqualTo(Signin.SignType.SCAN);
        assertThat(s.getRemark()).isNull(); // 宽松：不匹配仍签到，仅不加名单标记
    }

    @Test
    void scan_noRosterConfigured_behavesAsBefore() {
        when(activityRepo.existsById(1L)).thenReturn(true);
        when(rosterRepo.findByActivityId(1L)).thenReturn(List.of()); // 未配置名单
        var t = service.generateToken(1L);
        Signin s = service.scanSignin(t.getToken(), "张三", "2025001",
                Map.of("姓名", "张三", "学号", "2025001"));
        assertThat(s.getSignType()).isEqualTo(Signin.SignType.SCAN);
        assertThat(s.getRemark()).isNull();
    }

    @Test
    void backfill_skipsRosterRowAlreadySigned() {
        // 名单行 r1（张三/2025001）已有签到记录 → 补签跳过；r2（李四/2025002）无 → 补签 1 条
        SigninRoster r1 = rosterOf("{\"姓名\":\"张三\",\"学号\":\"2025001\"}");
        r1.setId(1L);
        SigninRoster r2 = rosterOf("{\"姓名\":\"李四\",\"学号\":\"2025002\"}");
        r2.setId(2L);
        when(rosterRepo.findById(1L)).thenReturn(java.util.Optional.of(r1));
        when(rosterRepo.findById(2L)).thenReturn(java.util.Optional.of(r2));
        Signin existing = new Signin();
        existing.setName("张三");
        existing.setStudentNo("2025001");
        when(signinRepo.findByActivityId(1L)).thenReturn(List.of(existing));

        int n = rosterService.backfill(1L, List.of(1L, 2L), 100L);
        assertThat(n).isEqualTo(1);
        verify(signinRepo, times(1)).save(any(Signin.class));
    }

    @Test
    void backfill_bothRowsUnsigned_savesBoth() {
        SigninRoster r1 = rosterOf("{\"姓名\":\"张三\",\"学号\":\"2025001\"}");
        r1.setId(1L);
        SigninRoster r2 = rosterOf("{\"姓名\":\"李四\",\"学号\":\"2025002\"}");
        r2.setId(2L);
        when(rosterRepo.findById(1L)).thenReturn(java.util.Optional.of(r1));
        when(rosterRepo.findById(2L)).thenReturn(java.util.Optional.of(r2));
        when(signinRepo.findByActivityId(1L)).thenReturn(List.of());

        int n = rosterService.backfill(1L, List.of(1L, 2L), 100L);
        assertThat(n).isEqualTo(2);
        verify(signinRepo, times(2)).save(any(Signin.class));
    }
}
