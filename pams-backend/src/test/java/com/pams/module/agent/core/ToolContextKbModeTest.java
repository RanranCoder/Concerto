package com.pams.module.agent.core;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对话模式（知识库问答，D12）的确定性回归 —— 对应知识库方案 **R1「静默降级」**。
 *
 * <p><b>这是本方案风险最高的一处，值得说清楚为什么</b>：
 * {@code ToolContext.normalizeMode} 原实现只识别 {@code readonly}，其余一律回退 {@code standard}。
 * 如果新增档位时<b>只改前端、没给后端加分支</b>，那么 {@code mode=kb} 会静默落进标准模式：
 * 用户以为自己在"查资料"，模型手里却握着 10 个写工具 ——
 * 而<b>编译通过、运行正常、日志无异常</b>，没有任何东西会告诉任何人出了问题。
 *
 * <p>这正是 D11 第 ③ 类「语义变更静默失效」：改了行为、没改契约、不报错。
 * 端到端脚本也抓不住它（跑起来"能用"，只是能力集不对），只能在单元层面钉。
 *
 * <p>本测试钉四件事：
 * <ol>
 *   <li>{@code kb} 被正确识别（<b>不被归一化成 standard</b>）；</li>
 *   <li>{@code kb} 与 {@code readonly} <b>语义不重叠</b>（都会挡写，但不是一个档位）；</li>
 *   <li>{@code derive()} 与 {@code withMode()} 都透传 —— derive 漏传是第一轮就失效的经典坑；</li>
 *   <li>{@link ToolRegistry} 对 {@code kb} 构成<b>物理双保险</b>（即便绕过编排层分流也写不进库）。</li>
 * </ol>
 */
class ToolContextKbModeTest {

    private static final GrantedAuthority P_WRITE = () -> "activity:edit";
    private static final GrantedAuthority P_READ = () -> "activity:view";

    private static AgentTool stub(String name, boolean write) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String runningLabel() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parametersSchema() { return Map.of(); }
            @Override public Sensitivity sensitivity() { return Sensitivity.NORMAL; }
            @Override public List<String> requiredPermissions() {
                return write ? List.of(P_WRITE.getAuthority()) : List.of(P_READ.getAuthority());
            }
            @Override public boolean writeOperation() { return write; }
            @Override public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
                return ToolResult.ok("ok");
            }
        };
    }

    private static ToolRegistry registry() {
        return new ToolRegistry(List.of(
                stub("createActivity", true),
                stub("queryActivityOverview", false)));
    }

    private static ToolContext ctx(String mode) {
        // loginUser 传 null 是刻意的：只验模式分支，不涉及脱敏/领导角色
        return new ToolContext(null, List.of(P_WRITE, P_READ), null, null, mode);
    }

    private static AgentTool tool(ToolRegistry r, String name) {
        return r.find(name).orElseThrow();
    }

    /* ------------------------------------------------------ ① kb 必须被识别 */

    @Test
    void kbIsRecognizedAndNotDowngradedToStandard() {
        assertThat(ctx("kb").getMode())
                .as("核心断言：kb 绝不能被归一化成 standard（否则静默持有写工具）")
                .isEqualTo(ToolContext.MODE_KB)
                .isNotEqualTo(ToolContext.MODE_STANDARD);

        assertThat(ctx(ToolContext.MODE_KB).isKb()).isTrue();
        assertThat(ctx("KB").isKb()).as("大小写不敏感").isTrue();
        assertThat(ctx("  kb  ").isKb()).as("容忍首尾空白").isTrue();
    }

    @Test
    void kbDoesNotLeakWriteCapability() {
        ToolRegistry r = registry();
        ToolContext kb = ctx(ToolContext.MODE_KB);

        assertThat(r.visibleTo(tool(r, "createActivity"), kb))
                .as("对话模式下写类工具必须不可见")
                .isFalse();
        assertThat(r.visibleTools(kb)).extracting(AgentTool::name)
                .as("给 LLM 的 tools 数组里不允许出现写类工具")
                .containsExactly("queryActivityOverview");
    }

    /** 双保险：即便将来有代码路径绕过编排层分流直接 dispatch，kb 也写不进库 */
    @Test
    void kbBlocksDispatchWithItsOwnMessage() {
        ToolRegistry r = registry();

        assertThatThrownBy(() -> r.dispatch("createActivity", ctx(ToolContext.MODE_KB), Map.of()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("对话模式")
                .as("文案必须与只读模式区分：这是任务轴切换，不是权限被收走")
                .hasMessageNotContaining("只读");
    }

    /* ------------------------------------------- ② 两个模式语义不重叠 */

    /**
     * 对话模式与只读模式都会挡写，但**不是一个档位**。
     *
     * <p>把它们混为一谈会让两处逻辑串味：只读模式不检索知识库，
     * 对话模式也不该被"因为像只读所以复用只读分支"处理。这条断言把标志位分开钉住。
     */
    @Test
    void kbAndReadonlyAreDistinctFlags() {
        ToolContext kb = ctx(ToolContext.MODE_KB);
        ToolContext ro = ctx(ToolContext.MODE_READONLY);

        assertThat(kb.isKb()).isTrue();
        assertThat(kb.isReadonly()).as("对话模式不是只读模式").isFalse();

        assertThat(ro.isReadonly()).isTrue();
        assertThat(ro.isKb()).as("只读模式不是对话模式").isFalse();
    }

    /* ------------------------------------------- ③ derive / withMode 透传 */

    /**
     * 编排器必然执行 {@code ctx.derive(convId, streamId)} 回填会话。
     * 若 derive 忘了透传 mode，对话档位在第一轮就会失效
     * （Controller 造的 ctx 有 kb，进编排器就没了）—— 本改造最阴的坑。
     */
    @Test
    void modeSurvivesDerive() {
        ToolContext derived = ctx(ToolContext.MODE_KB).derive(42L, "st-1");

        assertThat(derived.isKb()).as("derive 必须透传 kb").isTrue();
        assertThat(derived.getConversationId()).isEqualTo(42L);
        assertThat(derived.getStreamId()).isEqualTo("st-1");
        assertThat(registry().visibleTo(tool(registry(), "createActivity"), derived)).isFalse();
    }

    @Test
    void withModeSwitchesAcrossAllThreeModes() {
        ToolRegistry r = registry();

        ToolContext kb = ctx(ToolContext.MODE_STANDARD).withMode("kb");
        assertThat(kb.isKb()).isTrue();
        assertThat(r.visibleTo(tool(r, "createActivity"), kb)).isFalse();

        ToolContext ro = kb.withMode("readonly");
        assertThat(ro.isReadonly()).isTrue();
        assertThat(ro.isKb()).as("切走后不再是 kb").isFalse();

        ToolContext std = ro.withMode("standard");
        assertThat(std.isReadonly()).isFalse();
        assertThat(std.isKb()).isFalse();
        assertThat(r.visibleTo(tool(r, "createActivity"), std)).isTrue();
    }

    /* ------------------------------------------- ④ 向后兼容不回退 */

    /**
     * 未知值与空值仍回退 standard —— 这是刻意保留的历史语义：
     * 老客户端不发 mode 字段时不能突然写不了数据（那会是另一种静默降级）。
     * 真正的异常输入会留 WARN 日志，便于排查"档位传错"。
     */
    @Test
    void unknownModeStillFallsBackToStandardNotKb() {
        assertThat(ctx(null).isKb()).isFalse();
        assertThat(ctx(null).getMode()).isEqualTo(ToolContext.MODE_STANDARD);

        assertThat(ctx("").isKb()).isFalse();
        assertThat(ctx("kb_").isKb()).as("脏值不能被当成 kb").isFalse();
        assertThat(ctx("knowledge").isKb()).isFalse();
        assertThat(ctx("chat").isKb())
                .as("chat 不是合法取值（会与 /agent/chat 端点语义撞车）")
                .isFalse();
    }

    @Test
    void isKbIsFalseForNullOrAbsentContext() {
        // visibleTo(null) 已有守卫；这里明确 kb 判定不会 NPE
        assertThat(ctx(ToolContext.MODE_STANDARD).isKb()).isFalse();
        assertThat(registry().visibleTo(tool(registry(), "createActivity"), null)).isFalse();
    }
}
