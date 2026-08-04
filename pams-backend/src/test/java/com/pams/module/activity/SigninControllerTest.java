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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SigninController.scan 兼容新旧格式：
 * - 旧格式 {token, name, studentNo}（向后兼容）
 * - 新格式 {token, fields: {姓名, 学号, 手机号, 班级, 身份}}（核验字段动态校验）
 * /scan 是 permitAll 公开接口，直接构造控制器 + mock 服务验证参数映射与 400 分支。
 * 应签名单宽松匹配的标记逻辑在 SigninService（见 SigninScanRosterTest）。
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
        Map<String, Object> body = new HashMap<>();
        body.put("token", "some-token");
        body.put("name", "甲".repeat(51));
        assertThatThrownBy(() -> controller.scan(body))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
        verify(service, never()).scanSignin(any(), any(), any(), anyMap());
    }

    @Test
    void scan_nameExactly50_ok() {
        Signin saved = new Signin();
        saved.setName("乙".repeat(50));
        when(service.scanSignin(eq("some-token"), eq("乙".repeat(50)), eq("2025001"), anyMap()))
                .thenReturn(saved);
        Map<String, Object> body = new HashMap<>();
        body.put("token", "some-token");
        body.put("name", "乙".repeat(50));
        body.put("studentNo", "2025001");
        var result = controller.scan(body);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getName()).isEqualTo("乙".repeat(50));
    }

    @Test
    void scan_blankName_throws400() {
        Map<String, Object> body = new HashMap<>();
        body.put("token", "some-token");
        body.put("name", "   ");
        assertThatThrownBy(() -> controller.scan(body))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
        verify(service, never()).scanSignin(any(), any(), any(), anyMap());
    }

    @Test
    void scan_trimsWhitespaceBeforeService() {
        when(service.scanSignin(eq("t"), eq("张三"), isNull(), anyMap())).thenReturn(new Signin());
        Map<String, Object> body = new HashMap<>();
        body.put("token", "t");
        body.put("name", "  张三  ");
        controller.scan(body);
        verify(service).scanSignin(eq("t"), eq("张三"), isNull(), anyMap());
    }

    @Test
    void scan_withFields_newFormat_mapsToServiceColumns() {
        Map<String, Object> body = new HashMap<>();
        body.put("token", "t");
        Map<String, String> fields = new HashMap<>();
        fields.put("姓名", "张三");
        fields.put("学号", "2025001");
        fields.put("手机号", "13800000000");
        fields.put("班级", "一班");
        fields.put("身份", "党员");
        body.put("fields", fields);

        // 匹配应签名单时 service 会在 remark 追加"（应签名单）"，这里模拟该行为验证响应透传
        Signin saved = new Signin();
        saved.setName("张三");
        saved.setStudentNo("2025001");
        saved.setPhone("13800000000");
        saved.setClassName("一班");
        saved.setIdentityType("党员");
        saved.setRemark("（应签名单）");
        when(service.scanSignin(eq("t"), eq("张三"), eq("2025001"), anyMap())).thenReturn(saved);

        var result = controller.scan(body);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getName()).isEqualTo("张三");
        assertThat(result.getData().getStudentNo()).isEqualTo("2025001");
        assertThat(result.getData().getPhone()).isEqualTo("13800000000");
        assertThat(result.getData().getClassName()).isEqualTo("一班");
        assertThat(result.getData().getIdentityType()).isEqualTo("党员");
        // 宽松匹配命中名单 → remark 含"应签名单"
        assertThat(result.getData().getRemark()).isEqualTo("（应签名单）");
        verify(service).scanSignin(eq("t"), eq("张三"), eq("2025001"), argThat(fields::equals));
    }

    @Test
    void scan_withFields_nameOnlyInFields_ok() {
        // 新格式：姓名只放在 fields 里，不传顶层 name
        Map<String, Object> body = new HashMap<>();
        body.put("token", "t");
        Map<String, String> fields = new HashMap<>();
        fields.put("姓名", "李四");
        body.put("fields", fields);

        Signin saved = new Signin();
        saved.setName("李四");
        when(service.scanSignin(eq("t"), eq("李四"), isNull(), anyMap())).thenReturn(saved);

        var result = controller.scan(body);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getName()).isEqualTo("李四");
    }

    @Test
    void scan_withFields_notMatchingRoster_stillSigns() {
        // 宽松策略：不匹配名单仍签到（remark 无名单标记），controller 始终放行给 service
        Map<String, Object> body = new HashMap<>();
        body.put("token", "t");
        Map<String, String> fields = new HashMap<>();
        fields.put("姓名", "王五");
        fields.put("学号", "9999999");
        body.put("fields", fields);

        Signin saved = new Signin();
        saved.setName("王五");
        saved.setStudentNo("9999999");
        when(service.scanSignin(eq("t"), eq("王五"), eq("9999999"), anyMap())).thenReturn(saved);

        var result = controller.scan(body);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getName()).isEqualTo("王五");
        assertThat(result.getData().getRemark()).isNull();
    }

    @Test
    void scan_withFields_blankName_throws400() {
        // 新格式 fields 缺"姓名"：controller 在进入 service 前拦截
        Map<String, Object> body = new HashMap<>();
        body.put("token", "t");
        Map<String, String> fields = new HashMap<>();
        fields.put("学号", "2025001");
        body.put("fields", fields);
        assertThatThrownBy(() -> controller.scan(body))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
        verify(service, never()).scanSignin(any(), any(), any(), anyMap());
    }
}
