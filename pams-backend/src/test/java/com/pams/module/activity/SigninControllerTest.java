package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.controller.SigninController;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.service.SigninService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * SigninController.scan 姓名长度校验（Task 6 联调 minor）：
 * /scan 是 permitAll 公开接口，直接构造控制器 + mock 服务验证 400 分支，
 * 无需拉起 Spring 上下文，与 SigninTokenTest（服务层）互补。
 */
class SigninControllerTest {

    SigninService service;
    SigninController controller;

    @BeforeEach
    void setup() {
        service = mock(SigninService.class);
        controller = new SigninController(service);
    }

    @Test
    void scan_nameTooLong_throws400() {
        Map<String, String> body = new HashMap<>();
        body.put("token", "some-token");
        body.put("name", "甲".repeat(51));
        assertThatThrownBy(() -> controller.scan(body))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
        verify(service, never()).scanSignin(any(), any(), any());
    }

    @Test
    void scan_nameExactly50_ok() {
        Signin saved = new Signin();
        saved.setName("乙".repeat(50));
        when(service.scanSignin("some-token", "乙".repeat(50), "2025001"))
                .thenReturn(saved);
        Map<String, String> body = new HashMap<>();
        body.put("token", "some-token");
        body.put("name", "乙".repeat(50));
        body.put("studentNo", "2025001");
        var result = controller.scan(body);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getName()).isEqualTo("乙".repeat(50));
    }

    @Test
    void scan_blankName_throws400() {
        Map<String, String> body = new HashMap<>();
        body.put("token", "some-token");
        body.put("name", "   ");
        assertThatThrownBy(() -> controller.scan(body))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
        verify(service, never()).scanSignin(any(), any(), any());
    }

    @Test
    void scan_trimsWhitespaceBeforeService() {
        when(service.scanSignin("t", "张三", null)).thenReturn(new Signin());
        Map<String, String> body = new HashMap<>();
        body.put("token", "t");
        body.put("name", "  张三  ");
        controller.scan(body);
        verify(service).scanSignin("t", "张三", null);
    }
}
