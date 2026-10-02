package com.pams.module.agent.core;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读模式（UI v2 §E「单侧控制点」）的确定性回归。
 *
 * <p><b>为什么不写成端到端脚本</b>：这条逻辑是纯分支判断，用 HTTP 跑一遍只能覆盖
 * "某一时刻、某一个角色"的一条路径；而它的失败模式恰恰是<b>静默</b>的 ——
 * mode 在某个环节漏传，写工具就重新可见，没有任何报错。单测能把
 * 「漏传」「错误值」「derive 丢字段」这三种典型退化钉死在编译+测试期。
 *
 * <p><b>实测过的那条最容易漏的路径</b>：编排器必然执行
 * {@code ctx.derive(convId, streamId)} 回填会话，如果 derive 忘了透传 mode，
 * 只读档位在第一轮就会失效（Controller 造的 ctx 有 mode，进编排器就没了）。
 * 见 {@link #modeSurvivesDerive()}。
 */
class ToolRegistryReadonlyModeTest {

    private static final GrantedAuthority P_WRITE = () -> "activity:edit";
    private static final GrantedAuthority P_READ = () -> "activity:view";

    /** 最小 AgentTool 桩：只关心 name / writeOperation / requiredPermissions 三个判定输入 */
    private static AgentTool stub(String name, boolean write, GrantedAuthority... perms) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String runningLabel() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parametersSchema() { return Map.of(); }
            @Override public Sensitivity sensitivity() { return Sensitivity.NORMAL; }
            @Override public List<String> requiredPermissions() {
                return List.of(perms).stream().map(GrantedAuthority::getAuthority).toList();
            }
            @Override public boolean writeOperation() { return write; }
            @Override public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
                return ToolResult.ok("ok");
            }
        };
    }

    private static ToolRegistry registry() {
        return new ToolRegistry(List.of(
                stub("createActivity", true, P_WRITE),
                stub("queryActivityOverview", false, P_READ)));
    }

    private static ToolContext ctx(String mode) {
        // loginUser 传 null 是刻意的：本测试只验可见性判定，不涉及脱敏/领导角色分支
        return new ToolContext(null, List.of(P_WRITE, P_READ), null, null, mode);
    }

    private static AgentTool tool(ToolRegistry r, String name) {
        return r.find(name).orElseThrow();
    }

    @Test
    void readonlyHidesWriteToolsYetKeepsReadTools() {
        ToolRegistry r = registry();
        ToolContext ro = ctx(ToolContext.MODE_READONLY);

        assertThat(r.visibleTo(tool(r, "createActivity"), ro))
                .as("只读模式下写类工具必须不可见（不可见 ⇒ 不可诱导）")
                .isFalse();
        assertThat(r.visibleTo(tool(r, "queryActivityOverview"), ro))
                .as("只读模式不该影响读类工具")
                .isTrue();
        assertThat(r.visibleTools(ro)).extracting(AgentTool::name)
                .as("给 LLM 的 tools 数组里不允许出现写类工具")
                .containsExactly("queryActivityOverview");
    }

    @Test
    void standardKeepsWriteToolsVisible() {
        ToolRegistry r = registry();
        ToolContext std = ctx(ToolContext.MODE_STANDARD);

        assertThat(r.visibleTo(tool(r, "createActivity"), std)).isTrue();
        assertThat(r.visibleTools(std)).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("createActivity", "queryActivityOverview");
    }

    @Test
    void readonlyBlocksDispatchWithItsOwnMessage() {
        ToolRegistry r = registry();
        // dispatch 里还有一道独立断言（防"被诱导到"），只读模式必须也走这里
        assertThatThrownBy(() -> r.dispatch("createActivity", ctx(ToolContext.MODE_READONLY), Map.of()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("只读");
    }

    @Test
    void modeSurvivesDerive() {
        // 编排器第 148 行必然 derive(convId, streamId)；漏透传 mode 是本改造最阴的坑
        ToolContext derived = ctx(ToolContext.MODE_READONLY).derive(42L, "st-1");

        assertThat(derived.isReadonly()).as("derive 必须透传 mode").isTrue();
        assertThat(derived.getConversationId()).isEqualTo(42L);
        assertThat(derived.getStreamId()).isEqualTo("st-1");
        assertThat(registry().visibleTo(tool(registry(), "createActivity"), derived)).isFalse();
    }

    @Test
    void unknownOrMissingModeFallsBackToStandard() {
        // 默认开放写能力 —— 与改造前行为一致。若默认成只读，
        // 老客户端（不发 mode 字段）会突然写不了数据，属"静默降级"
        assertThat(ctx(null).isReadonly()).isFalse();
        assertThat(ctx("").isReadonly()).isFalse();
        assertThat(ctx("READONLY_").isReadonly()).isFalse();
        assertThat(ctx("readonly").getMode()).isEqualTo(ToolContext.MODE_READONLY);
        assertThat(ctx("READONLY").getMode()).as("大小写不敏感").isEqualTo(ToolContext.MODE_READONLY);
        assertThat(ctx("  readonly  ").isReadonly()).as("容忍首尾空白").isTrue();
    }

    @Test
    void withModeSwitchesBothWays() {
        ToolRegistry r = registry();
        ToolContext ro = ctx(ToolContext.MODE_STANDARD).withMode("readonly");
        assertThat(r.visibleTo(tool(r, "createActivity"), ro)).isFalse();
        assertThat(r.visibleTo(tool(r, "createActivity"), ro.withMode("standard"))).isTrue();
    }
}
