package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.repository.AgentMessageRepository;
import com.pams.module.agent.core.ToolContext;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时序回归：<b>本轮执行过的工具，决定这一轮的留存档位</b>（D4 / 决策 6）。
 *
 * <p><b>为什么必须有这一层（只测 {@code Sensitivity.max} 是锁不住的）</b>：缺陷的形态是
 * "策略写了但没接线" —— 原实现 {@code pendingPlan != null ? NORMAL : NORMAL} 两个分支同值，
 * {@code max(...)} 再正确也不会被调用；把编排器那一行删掉，纯函数测试依然全绿。
 * 所以这里跑的是<b>真实编排器 + 真实工具注册表 + 真实 ConversationStore + H2</b>，
 * 只把 LLM 换成一个照剧本回话的假 provider，最后从库里读回那条 assistant 消息的
 * {@code sensitivity} 列 —— 断言的落点就是"这一轮到底按什么档位存的"。
 *
 * <p><b>为什么不用 Mockito</b>：本沙箱里 Mockito 的 inline mock maker 无法 self-attach
 * （{@code Could not initialize plugin: interface org.mockito.plugins.MockMaker}），
 * 仓库里既有的 Mockito 测试（如 {@code ChatRefsResolverTest}）在这里同样全红。
 * 换成"真实 Bean + 一个手写假 provider"后，测试不依赖字节码增强，跑得动也更接近真实链路。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentOrchestratorTurnSensitivityTest {

    /** 读类 + SENSITIVE：考勤明细关联到具体人（工具类注释明确写了） */
    private static final String SENSITIVE_TOOL = "queryAttendance";
    /** 读类 + NORMAL：不需要任何权限码（default requiredPermissions = 空），且只查本站内通知表 */
    private static final String NORMAL_TOOL = "queryNotifications";

    @Autowired
    AgentOrchestrator orchestrator;
    @Autowired
    AgentMessageRepository messages;
    @Autowired
    ScriptedProvider llm;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetScript() {
        com.pams.common.OrgContext.set(1L);
        llm.reset();
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenant() { com.pams.common.OrgContext.clear(); }

    /* ------------------------------------------------------------------ 用例 */

    @Test
    void executedSensitiveToolMakesTheWholeTurnSensitive() {
        llm.script(toolCall(SENSITIVE_TOOL), finalReply());

        Map<String, Object> res = chat("查一下考勤明细", true);

        assertCompleted(res);
        AgentMessage saved = assistantMessage(res);
        assertThat(toolStatusOf(saved, SENSITIVE_TOOL))
                .as("必须先确认这个工具真的被执行了（DENIED 不算执行，档位语义完全不同）")
                .isNotEqualTo("DENIED");
        assertThat(saved.getSensitivity())
                .as("执行过 SENSITIVE 工具 → 这一轮的答复与思维链都只能按敏感档留存")
                .isEqualTo("sensitive");
        // 顺带把"推理落库"这条链一起串起来验：provider → ThinkingTrace → thinking_json
        assertThat(stored(saved.getThinkingJson()))
                .as("假 provider 给的推理必须随消息落库（敏感档下短文本原样保留）")
                .contains("推理原文");
    }

    @Test
    void executedNormalToolKeepsTheTurnNormal() {
        llm.script(toolCall(NORMAL_TOOL), finalReply());

        Map<String, Object> res = chat("有什么新通知", true);

        assertCompleted(res);
        AgentMessage saved = assistantMessage(res);
        assertThat(toolStatusOf(saved, NORMAL_TOOL))
                .as("确认走的是执行分支而不是 DENIED（否则这条断言会因为'没执行'而恰好通过）")
                .isEqualTo("OK");
        assertThat(saved.getSensitivity())
                .as("普通工具不能被无端升级成敏感档（否则正文会被截成摘要）")
                .isEqualTo("normal");
    }

    /**
     * 一轮里多个工具 → 取<b>最严</b>的那个。
     *
     * <p>这是"取 max"语义唯一的运行期证据：先执行的 NORMAL 工具不能把后面的 SENSITIVE 覆盖掉
     * （若实现写成"取最后一个"或"取第一个"，这条会红）。
     */
    @Test
    void strictestToolWinsWhenSeveralRanInOneTurn() {
        llm.script(toolCalls(NORMAL_TOOL, SENSITIVE_TOOL), finalReply());

        Map<String, Object> res = chat("先看待办再看考勤", true);

        assertCompleted(res);
        AgentMessage saved = assistantMessage(res);
        assertThat(toolStatusOf(saved, SENSITIVE_TOOL)).isNotEqualTo("DENIED");
        assertThat(toolStatusOf(saved, NORMAL_TOOL)).isEqualTo("OK");
        assertThat(saved.getSensitivity())
                .as("NORMAL 与 SENSITIVE 都执行过 → 取更严的 sensitive")
                .isEqualTo("sensitive");
    }

    /**
     * 被拒 / 不可见的工具<b>不算执行过</b>，不进档位计算。
     *
     * <p>构造方式：同一个 SENSITIVE 工具，但这次调用者没有 {@code schedule:check}
     * → 工具对它不可见 → 编排器走 DENIED 分支（不 dispatch、不计档位）。
     * 若实现把"模型想调的"也算进来，这里会变成 sensitive —— 用户会因为模型的一次幻觉
     * 而丢掉整轮记录。
     */
    @Test
    void deniedToolDoesNotCountTowardTheTurnSensitivity() {
        llm.script(toolCall(SENSITIVE_TOOL), finalReply());

        Map<String, Object> res = chat("试试调一个我没权限的工具", false);

        assertCompleted(res);
        AgentMessage saved = assistantMessage(res);
        assertThat(toolStatusOf(saved, SENSITIVE_TOOL))
                .as("必须是 DENIED：证明走的是'不可见'分支（本用例的前提）")
                .isEqualTo("DENIED");
        assertThat(saved.getSensitivity())
                .as("未执行的工具不算数：档位保持 normal")
                .isEqualTo("normal");
    }

    /** 基线：整轮没有任何工具调用 → normal（策略不能凭空收紧） */
    @Test
    void answeringWithoutAnyToolStaysNormal() {
        llm.script(finalReply());

        Map<String, Object> res = chat("你好", true);

        assertCompleted(res);
        assertThat(assistantMessage(res).getSensitivity()).isEqualTo("normal");
    }

    /* ------------------------------------------------------------------ 工具 */

    private Map<String, Object> chat(String message, boolean withSchedulePermission) {
        // conversationId 传 null：走真实的建会话路径（也顺带覆盖"首轮无会话"这条分支）
        return orchestrator.chatSync(message, null, ctx(withSchedulePermission), List.of(), false);
    }

    private ToolContext ctx(boolean withSchedulePermission) {
        LoginUser lu = new LoginUser(1L, "turn", "档位测试", "DIRECTOR", 4, null, null, 1L);
        List<SimpleGrantedAuthority> authorities = withSchedulePermission
                ? List.of(new SimpleGrantedAuthority("schedule:check"))
                : List.of();
        return new ToolContext(lu, authorities, null, null, ToolContext.MODE_STANDARD);
    }

    /**
     * 必须确认真的走完了工具循环。
     *
     * <p>编排器把整个工具循环包在 {@code try/catch} 里，异常会被改写成"处理时出现异常…"的
     * 友好话术 —— 那样断言 sensitivity 就可能"因为没跑到"而误判，所以先看答复对不对。
     */
    private void assertCompleted(Map<String, Object> res) {
        assertThat(res.get("reply"))
                .as("必须走到最终答复（异常会被 catch 吞掉并改成错误话术，掩盖后续断言）")
                .isEqualTo("最终答复");
        assertThat(res.get("degraded")).isEqualTo(false);
    }

    private AgentMessage assistantMessage(Map<String, Object> res) {
        Long messageId = (Long) res.get("messageId");
        assertThat(messageId).as("收官必须落了一条 assistant 消息").isNotNull();
        return messages.findById(messageId).orElseThrow();
    }

    /** H2 把 JSON 列当字符串存（读回多一层引号），与 MySQL 语义不同：在测试边界剥掉 */
    private String stored(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            try {
                return mapper.readValue(s, String.class);
            } catch (Exception ignored) {
                return raw;
            }
        }
        return raw;
    }

    /**
     * 从落库的工具行里读回某个工具的 status。
     *
     * <p>用途是"防止断言因为错误的原因通过"：档位只看"执行 vs 未执行"，
     * 而 DENIED 与执行的分支在实现上相差一个 {@code continue} —— 不把状态钉死，
     * 一条"sensitive 工具被拒"的用例会与"sensitive 工具执行了"的用例看起来一模一样。
     * 注意 {@code FAILED} 也算"执行过"（槽位规则是"未可见/被拒不算"，不是"失败不算"）。
     */
    private String toolStatusOf(AgentMessage saved, String toolName) {
        assertThat(saved.getToolCalls()).as("必须落了工具行").isNotNull();
        try {
            var arr = mapper.readTree(stored(saved.getToolCalls()));
            for (var node : arr) {
                if (toolName.equals(node.path("tool").asText())) {
                    return node.path("status").asText();
                }
            }
        } catch (Exception e) {
            throw new AssertionError("工具行不是合法 JSON：" + saved.getToolCalls(), e);
        }
        throw new AssertionError("工具行里没有 " + toolName + "：" + stored(saved.getToolCalls()));
    }

    private LlmProvider.ChatResponse toolCall(String toolName) {
        return toolCalls(toolName);
    }

    private LlmProvider.ChatResponse toolCalls(String... toolNames) {
        List<LlmProvider.ToolCallRequest> calls = new ArrayList<>();
        for (int i = 0; i < toolNames.length; i++) {
            calls.add(new LlmProvider.ToolCallRequest("call_" + i, toolNames[i], Map.of()));
        }
        // 带上推理原文：真实 provider 在思考模式下会同时给 content 与 reasoning_content
        return new LlmProvider.ChatResponse("", calls, "deepseek", false, "推理原文");
    }

    private LlmProvider.ChatResponse finalReply() {
        return new LlmProvider.ChatResponse("最终答复", List.of(), "deepseek", false, null);
    }

    /* ------------------------------------------------------------------ 假 provider */

    /**
     * 照剧本回话的 provider（手写替身，不用 Mockito）。
     *
     * <p>它替换的是 {@code primaryProvider} 这个 Bean（{@code @Primary}），
     * 其余链路（{@code AgentLlmCaller} 的重试/降级/流式合并、编排器、工具注册表）全是真的 ——
     * 这正是本测试想要覆盖的部分。
     */
    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        ScriptedProvider scriptedPrimaryProvider() {
            return new ScriptedProvider();
        }
    }

    static class ScriptedProvider implements LlmProvider {

        private final List<ChatResponse> script = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        void reset() {
            script.clear();
            calls.set(0);
        }

        void script(ChatResponse... responses) {
            script.clear();
            script.addAll(List.of(responses));
            calls.set(0);
        }

        @Override
        public String name() {
            return "deepseek";
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("本测试只覆盖流式路径");
        }

        @Override
        public ChatResponse chatStream(ChatRequest request, java.util.function.Consumer<String> onDelta,
                                       java.util.function.Consumer<String> onReasoning) {
            int i = calls.getAndIncrement();
            ChatResponse r = i < script.size() ? script.get(i) : script.get(script.size() - 1);
            if (r.content() != null && !r.content().isEmpty()) {
                onDelta.accept(r.content());
            }
            if (r.reasoningContent() != null) {
                onReasoning.accept(r.reasoningContent());
            }
            return r;
        }
    }
}
