package com.pams.module.agent.core;

import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.service.ActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 上下文注入（UI v2 输入框 `+`）的确定性回归。
 *
 * <p>这条链路的失败模式很特别：<b>不是报错，而是"用户以为附加成功了、模型却没看见"</b>。
 * 所以这里钉的不是"能不能解析"，而是四个静默失效点：
 * ①脏引用被跳过时<b>必须留下"已忽略"的说明</b>（否则用户与模型对世界的认知不一致）；
 * ②解析出的注记必须包含<b>裁决规则</b>（口述名称与附加对象冲突时听谁的）；
 * ③落库用的 references 必须与其他工具同构（前端渲染零改动的前提）；
 * ④<b>无权访问与不存在必须给同一句话</b>——否则会变成存在性枚举工具（权限审计 A1 / 知识库 G7）。
 *
 * <p>第 ④ 条是 2026-09-14 新增的：此前 {@code resolve} 不接收 {@code ToolContext}，
 * 前端传什么 ID 就解析什么活动，既不过 {@code ToolRegistry}、不留审计、也不校验权限。
 */
class ChatRefsResolverTest {

    private ActivityService service;
    private ChatRefsResolver resolver;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ActivityService.class);
        resolver = new ChatRefsResolver(service);
    }

    /** 活动可见性口径 = activity:view（与 ActivityController 同码） */
    private static ToolContext ctxWithActivityView() {
        return ctx("activity:view");
    }

    private static ToolContext ctx(String... codes) {
        List<SimpleGrantedAuthority> auths = new ArrayList<>();
        for (String c : codes) auths.add(new SimpleGrantedAuthority(c));
        return new ToolContext(null, auths, 1L, "st_test");
    }

    private static Activity activity(long id, String name) {
        Activity a = new Activity();
        a.setId(id);
        a.setName(name);
        a.setStatus(ActivityStatus.PLANNING);
        a.setStartDate(LocalDate.of(2026, 9, 20));
        a.setEndDate(LocalDate.of(2026, 9, 22));
        a.setLocation("行政楼 3F 报告厅");
        return a;
    }

    private static Map<String, Object> ref(String type, Object id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        return m;
    }

    @Test
    void activityRefProducesNoteAndReference() {
        when(service.getEntity(412L)).thenReturn(activity(412L, "第四十期入党积极分子培训班"));

        ChatRefsResolver.Resolved r = resolver.resolve(List.of(ref("activity", 412)), ctxWithActivityView());

        assertThat(r.note())
                .contains("【用户附加的上下文】")
                .contains("第四十期入党积极分子培训班")
                .contains("ID 412")
                // 裁决规则必须有：否则口述名称与附加对象冲突时模型行为不可预测
                .contains("以上面指定的为准");
        assertThat(r.references()).hasSize(1);
        assertThat(r.references().get(0))
                .containsEntry("type", "activity")
                .containsEntry("id", 412L)
                .containsEntry("label", "第四十期入党积极分子培训班")
                .containsEntry("link", "/activities/412");
    }

    @Test
    void numericStringIdIsAccepted() {
        // 前端 Select 的 value 是字符串，Controller 不做类型转换（透传给 resolver 归一化）
        when(service.getEntity(7L)).thenReturn(activity(7L, "开班仪式"));

        ChatRefsResolver.Resolved r = resolver.resolve(List.of(ref("activity", "7")), ctxWithActivityView());

        assertThat(r.note()).isNotBlank();
        assertThat(r.references().get(0)).containsEntry("id", 7L);
    }

    @Test
    void unsupportedTypeIsSkippedButExplained() {
        ChatRefsResolver.Resolved r = resolver.resolve(List.of(ref("member", 3)), ctxWithActivityView());

        // 不能静默吞掉：用户以为附加成功了、模型却没看见，是最隐蔽的失效方式
        assertThat(r.note()).contains("已忽略").contains("member");
        assertThat(r.references()).isEmpty();
    }

    @Test
    void missingEntityIsSkippedButExplained() {
        when(service.getEntity(anyLong())).thenThrow(new RuntimeException("not found"));

        ChatRefsResolver.Resolved r = resolver.resolve(List.of(ref("activity", 99)), ctxWithActivityView());

        assertThat(r.note()).contains("99").contains("已忽略");
        assertThat(r.references()).isEmpty();
    }

    /* ------------------------------------------------ ④ 可见性（权限审计 A1） */

    /**
     * 无 {@code activity:view} 时必须拒绝，且<b>不得触碰服务</b>。
     *
     * <p>"先取数据再判断"会把内容带进内存（可能进日志、可能被缓存），
     * 所以断言的是 {@code getEntity} 从未被调用 —— 这是"拿不到就不取"的直接证据。
     */
    @Test
    void withoutPermissionNothingIsFetchedAtAll() {
        when(service.getEntity(anyLong())).thenReturn(activity(412L, "不该被拿到的活动"));

        ChatRefsResolver.Resolved r = resolver.resolve(
                List.of(ref("activity", 412)), ctx("schedule:view"));

        assertThat(r.note()).contains("412").contains("已忽略");
        assertThat(r.references()).isEmpty();
        Mockito.verify(service, Mockito.never()).getEntity(anyLong());
    }

    /**
     * 关键安全断言：<b>"无权访问"与"不存在"必须不可区分</b>。
     *
     * <p>如果两者话术不同，攻击者就能把"无权访问"当存在性证明，顺序枚举出全部活动 ID。
     * 这条与知识库方案的 G7（三种拒答话术可区分 → 存在性泄露）是同一个问题。
     */
    @Test
    void noPermissionAndNotFoundAreIndistinguishable() {
        when(service.getEntity(anyLong())).thenThrow(new RuntimeException("not found"));
        ChatRefsResolver.Resolved notFound = resolver.resolve(
                List.of(ref("activity", 500)), ctxWithActivityView());

        ChatRefsResolver.Resolved forbidden = resolver.resolve(
                List.of(ref("activity", 500)), ctx("notice:view"));

        assertThat(forbidden.note())
                .as("无权与不存在的话术必须一字不差，否则变成存在性枚举工具")
                .isEqualTo(notFound.note());
    }

    /** ctx 为 null 时保守拒绝：这个方法可能被不持有上下文的路径调用，静默放行会成为新的越权口 */
    @Test
    void nullContextIsDeniedNotAllowed() {
        when(service.getEntity(anyLong())).thenReturn(activity(1L, "活动"));

        ChatRefsResolver.Resolved r = resolver.resolve(List.of(ref("activity", 1)), null);

        assertThat(r.references()).isEmpty();
        assertThat(r.note()).contains("已忽略");
    }

    @Test
    void malformedAndEmptyInputsYieldNothing() {
        ToolContext ctx = ctxWithActivityView();

        assertThat(resolver.resolve(null, ctx).empty()).isTrue();
        assertThat(resolver.resolve(List.of(), ctx).empty()).isTrue();
        assertThat(resolver.resolve(List.of(ref("activity", null)), ctx).empty()).isTrue();
        assertThat(resolver.resolve(List.of(ref(null, 1)), ctx).empty()).isTrue();

        // List.of 不接受 null 元素，这里用可变列表模拟"前端传了一行 null"的脏数据
        List<Map<String, Object>> withNullRow = new ArrayList<>();
        withNullRow.add(null);
        assertThat(resolver.resolve(withNullRow, ctx).empty()).isTrue();
    }

    @Test
    void capsAtFiveReferences() {
        for (long i = 1; i <= 8; i++) {
            when(service.getEntity(i)).thenReturn(activity(i, "活动" + i));
        }
        List<Map<String, Object>> many = new ArrayList<>();
        for (long i = 1; i <= 8; i++) many.add(ref("activity", i));

        ChatRefsResolver.Resolved r = resolver.resolve(many, ctxWithActivityView());

        // 上限与 Controller / 前端 addRef 的口径一致（MAX_REFS = 5）
        assertThat(r.references()).hasSize(ChatRefsResolver.MAX_REFS);
        assertThat(r.references()).extracting(m -> m.get("id")).containsExactly(1L, 2L, 3L, 4L, 5L);
    }
}
