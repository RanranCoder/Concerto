package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.service.SigninService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SigninTokenTest {

    SigninRepository repo;
    ActivityRepository activityRepo;
    SigninService service;

    @BeforeEach
    void setup() {
        repo = mock(SigninRepository.class);
        activityRepo = mock(ActivityRepository.class);
        service = new SigninService(repo, activityRepo);
    }

    @Test
    void generateToken_returnsUniqueToken() {
        var a = service.generateToken(1L);
        var b = service.generateToken(1L);
        assertThat(a.getToken()).isNotEqualTo(b.getToken());
        assertThat(a.getExpiresAt()).isAfter(java.time.LocalDateTime.now());
    }

    @Test
    void scanSignin_validToken_createsScannedRecord() {
        var t = service.generateToken(1L);
        when(activityRepo.existsById(1L)).thenReturn(true);
        when(repo.save(any(Signin.class))).thenAnswer(inv -> inv.getArgument(0));
        Signin s = service.scanSignin(t.getToken(), "张三", "2025001");
        assertThat(s.getSignType()).isEqualTo(Signin.SignType.SCAN);
        assertThat(s.getActivityId()).isEqualTo(1L);
        assertThat(s.getName()).isEqualTo("张三");
    }

    @Test
    void scanSignin_invalidToken_throws() {
        assertThatThrownBy(() -> service.scanSignin("bad-token", "张三", "2025001"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void refresh_invalidatesOldToken() {
        var first = service.generateToken(1L);
        service.generateToken(1L); // 刷新：同活动新令牌
        // 旧码已作废，扫旧码应报错
        assertThatThrownBy(() -> service.scanSignin(first.getToken(), "张三", "2025001"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void scanSignin_activityWithoutMatchingSigninId_succeeds() {
        // 回归：活动存在校验必须用 ActivityRepository（按 activityId），而非签到的自增 id。
        // 活动 id=2 存在但 signin 表无 id=2 的记录时，扫码也应成功。
        var t = service.generateToken(2L);
        when(activityRepo.existsById(2L)).thenReturn(true);
        when(repo.existsById(2L)).thenReturn(false);
        when(repo.save(any(Signin.class))).thenAnswer(inv -> inv.getArgument(0));
        Signin s = service.scanSignin(t.getToken(), "王五", "2025003");
        assertThat(s.getActivityId()).isEqualTo(2L);
        assertThat(s.getSignType()).isEqualTo(Signin.SignType.SCAN);
    }

    @Test
    void scanSignin_expiredToken_throws() {
        var t = service.generateToken(2L);
        // 手动把过期时间改为过去
        service.forceExpire(t.getToken());
        assertThatThrownBy(() -> service.scanSignin(t.getToken(), "李四", "2025002"))
                .isInstanceOf(BizException.class);
    }
}
