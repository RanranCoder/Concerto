package com.pams.module.agent.service;

import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.AgentActionPlan;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.ChatRefsResolver;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolRegistry;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.agent.core.WriteAgentTool;
import com.pams.module.agent.entity.AgentConversation;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 灵思编排器（业务设计方案 B0 核心 / D1 / D2 / D4 / D5 / D7）。
 *
 * <p>一次问答的完整链路：
 * <pre>
 *  ①推 THINKING（**必须在意图分类之前**，否则 TTFE 不达标）
 *  ②组装上下文（system + 最近 K 轮，内容已写时脱敏）
 *  ③调 LLM → 可能返回 tool_calls
 *  ④逐个派发工具（ToolRegistry.dispatch 内含 403 断言）
 *  ⑤工具结果过 PolicyGuard.egress 才进上下文（restricted 不出网 → 固定话术）
 *  ⑥写类工具返回 plan → TaskGate.createPlan → 推 PLAN_READY → 本轮结束（等用户确认）
 *  ⑦回到 ③，最多 maxToolIterations 轮
 *  ⑧落库消息 + 推 MESSAGE_DONE
 * </pre>
 *
 * <p><b>为什么不用 {@code @Async}</b>：需要显式携带 SecurityContext 与流控，
 * 且要在编排开始前就返回 streamId。故用 {@link com.pams.module.agent.config.AgentExecutorConfig}
 * 提供的 {@code agentExecutor}（已包 {@code DelegatingSecurityContextAsyncTaskExecutor}）。
 *
 * <p><b>降级策略</b>：主 provider 抛异常 → 重试 1 次 → 仍失败则用 StubProvider 的规则回复，
 * 并把 {@code degraded=true} 透传到前端（灵思 §3.5.1 必须明示"当前为规则模式"）。
 */
@Slf4j
@Service
public class AgentOrchestrator {

    /** 系统提示词——约束语气、边界、以及"必须用工具而不是编造" */
    private static final String SYSTEM_PROMPT = """
            你是「灵思」，一个党务管理系统的智能助手。你的职责是帮使用者处理日常事务。

            行为准则：
            1. 只依据工具返回的事实回答。工具没返回的信息，明确说"我没查到"，绝不编造具体数字、人名、日期。
            2. 涉及数据变更（加分、起草、排班、发布、创建）时，先说明你打算做什么；确认与参数补全由系统的确认清单和表单完成，你不需要自己索要"确认"二字，也不要替表单向用户提问。
            3. 发起创建/生成/发送类操作时，只要用户表达了意图（如"建个活动""发个通知"），<b>立即调用对应工具</b>——把用户已提到的字段填进参数，缺的留空；缺少参数时系统会弹出结构化表单请使用者填写。即使一个参数都没有也直接调用工具，<b>绝不为了凑参数而在对话里反问用户</b>。
            4. 回答用简洁的中文，不要客套话，不要复述用户的问题。
            5. 涉及人名、手机号、身份证号时不要罗列细节，概括即可。
            6. 如果问题超出现有工具能力范围，直接说明你能做什么，并给出可用的问法示例。
            """;

    private final ToolRegistry registry;
    private final LlmProvider primaryProvider;
    private final ConversationStore conversations;
    private final TaskGate taskGate;
    private final AuditService auditService;
    private final AgentEventPublisher publisher;
    private final PolicyGuard policyGuard;
    private final AgentProperties props;
    private final Executor agentExecutor;
    private final ChatRefsResolver refsResolver;
    /** 一次 LLM 调用（重试/降级/流式）—— 与 KbPipeline 共用，见 {@link AgentLlmCaller} */
    private final AgentLlmCaller llmCaller;
    /** 知识库问答流水线（对话档位） */
    private final KbPipeline kbPipeline;

    public AgentOrchestrator(ToolRegistry registry,
                             LlmProvider primaryProvider,
                             ConversationStore conversations,
                             TaskGate taskGate,
                             AuditService auditService,
                             AgentEventPublisher publisher,
                             PolicyGuard policyGuard,
                             AgentProperties props,
                             @Qualifier("agentExecutor") Executor agentExecutor,
                             ChatRefsResolver refsResolver,
                             AgentLlmCaller llmCaller,
                             KbPipeline kbPipeline) {
        this.registry = registry;
        this.primaryProvider = primaryProvider;
        this.conversations = conversations;
        this.taskGate = taskGate;
        this.auditService = auditService;
        this.publisher = publisher;
        this.policyGuard = policyGuard;
        this.props = props;
        this.agentExecutor = agentExecutor;
        this.refsResolver = refsResolver;
        this.llmCaller = llmCaller;
        this.kbPipeline = kbPipeline;
    }

    /* ===================================================================== 对外入口 */

    /**
     * 同步问答：阻塞直到产出最终答复。
     *
     * <p>用同步而非异步，是为了让 Controller 能直接返回 {@code reply}——
     * 过程事件（THINKING/TOOL_START/TOOL_END）在等待期间经 WebSocket 实时推送，
     * 因此"同步返回"与"过程可见"并不矛盾。
     */
    public Map<String, Object> chatSync(String message, Long conversationId, ToolContext ctx) {
        return chatSync(message, conversationId, ctx, List.of());
    }

    /**
     * 带「附加上下文」的问答（UI v2 输入框 `+` 选择实体）。
     *
     * <p>refs 形如 {@code [{type:'activity', id:412}]}，由前端从系统里点选产生。
     * 三处用途：①解析成 system 注记注入本轮 LLM 上下文（模型必须看得见才会用）
     * ②随用户消息落 refs_json（历史可追溯）③原样回传给前端渲染成引用 chip。
     */
    public Map<String, Object> chatSync(String message, Long conversationId, ToolContext ctx,
                                        List<Map<String, Object>> refs) {
        // streamId 记归属：补拉接口要校验"这个流是不是你的"（权限审计 A5）
        String streamId = publisher.newStream("st", ctx == null ? null : ctx.getUserId());
        return run(message, conversationId, ctx, streamId, refs);
    }

    /** 发起异步编排（用于显式要求后台执行的场景），立即返回 streamId */
    public String chatAsync(String message, Long conversationId, ToolContext ctx) {
        String streamId = publisher.newStream("st", ctx == null ? null : ctx.getUserId());
        agentExecutor.execute(() -> {
            try {
                run(message, conversationId, ctx, streamId, List.of());
            } catch (Exception e) {
                log.error("灵思异步编排失败：stream={}", streamId, e);
            }
        });
        return streamId;
    }

    /* ===================================================================== 主流程 */

    private Map<String, Object> run(String message, Long conversationId, ToolContext ctx, String streamId,
                                    List<Map<String, Object>> refs) {
        long startedAt = System.currentTimeMillis();

        // ===== ① TTFE：进入编排的第一时间就推 THINKING，不得等首个工具出结果 =====
        emit(ctx, AgentStreamEvent.thinking(streamId, publisher.nextSeq(streamId), "正在理解你的问题…"));

        // 会话：无则创建（标题取首条消息前 18 字）
        AgentConversation conversation;
        if (conversationId == null) {
            conversation = conversations.create(ctx.getUserId(), message);
        } else {
            conversation = conversations.require(conversationId, ctx.getUserId());
        }
        Long convId = conversation.getId();
        conversations.touch(convId);

        // 会话在此刻才真正确定（新会话首轮 conversationId=null，streamId 也是本方法内生成的）
        // → 回填 ctx。否则：首轮任务的 conversation_id 落 NULL、任务无法与流绑定（历史恢复失效）。
        ctx = ctx.derive(convId, streamId);

        // 用户消息落库（写时脱敏由 ConversationStore 内部完成）
        // 附加引用随消息落 refs_json：历史里能看到"当时选了哪个活动"，而不只是文字里的一个代词
        // ⚠️ ctx 一并传入：附加上下文过去是"直取 ID、无权限校验"的旁路（权限审计 A1）
        ChatRefsResolver.Resolved attached = refsResolver.resolve(refs, ctx);
        conversations.save(convId, AgentMessage.ROLE_USER, message, Sensitivity.NORMAL,
                conversations.summarize(message, 60), null, attached.references(), streamId, null, null,
                ctx.getMode(), null);

        List<Map<String, Object>> toolCallVo = new ArrayList<>();
        List<Map<String, Object>> allReferences = new ArrayList<>();
        /** V27：本轮答案的依据（服务端回填）。对话档位才有；办事型为空 */
        List<Map<String, Object>> citationsOut = new ArrayList<>();
        AgentActionPlan pendingPlan = null;
        Map<String, Object> pendingForm = null;
        String reply = "";
        String providerName = primaryProvider.name();
        boolean degraded = false;
        int firstToolSeq = -1;

        // ===== 模式分流（方案 §7.3）=====
        // 「对话」档位走独立流水线，**不进工具循环**。
        // 这么写（而不是把整个 try 包进 if/else 再缩进）是为了让既有办事型代码**零改动** ——
        // 那段循环里有 6 处踩过坑的细节（round 语义、DENIED 话术分流、plan 短路等），
        // 大范围缩进会产生一个几乎无法 review 的 diff。
        boolean kbMode = ctx.isKb();
        if (kbMode) {
            KbPipeline.KbOutcome kb = kbPipeline.run(message, convId, ctx, streamId);
            citationsOut.addAll(kb.citations());
            reply = kb.reply();
            if (kb.provider() != null) providerName = kb.provider();
            degraded = kb.degraded();
        }

        if (!kbMode)
        try {
            // ===== ② 组装 LLM 上下文 =====
            List<LlmProvider.ChatMessage> messages = buildContext(convId, message, ctx, attached.note());

            // 可见工具清单（不可见 ⇒ 不可诱导，D3 第 2 层）
            List<AgentTool> visible = registry.visibleTools(ctx);
            List<LlmProvider.ToolSpec> specs = toSpecs(visible);

            int iteration = 0;
            int maxIter = Math.max(props.getMaxToolIterations(), 1);

            while (true) {
                // 中止检查点（阶段边界）
                if (publisher.isAborted(streamId)) {
                    emit(ctx, AgentStreamEvent.aborted(streamId, publisher.nextSeq(streamId)));
                    reply = "已中止本次回答。";
                    break;
                }

                // ===== ③ 调 LLM（含重试 1 次 + 降级；文本增量按 round 实时推送） =====
                LlmProvider.ChatResponse response = callLlm(messages, specs, ctx, streamId, iteration);
                providerName = response.provider();
                degraded = response.degraded();

                // 无工具调用 → 这就是最终答复
                if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
                    reply = response.content() == null ? "" : response.content();
                    break;
                }

                // 轮数上限保护（防 LLM 死循环，D5）
                if (iteration >= maxIter) {
                    log.warn("灵思工具调用轮数达上限 {}，强制收尾：stream={}", maxIter, streamId);
                    reply = response.content() != null && !response.content().isBlank()
                            ? response.content()
                            : "任务较复杂，已执行到上限。以上是当前能查到的结果，如需继续请分步提问。";
                    break;
                }
                iteration++;

                // 把 LLM 的"我要调工具"回合放进上下文，保证多轮一致性。
                // ⚠️ 必须带 toolCalls：OpenAI 兼容协议要求 assistant(tool_calls) 与其后的
                // tool(tool_call_id) 成对出现，只放一句裸文本会被 DeepSeek 判为非法请求。
                messages.add(LlmProvider.ChatMessage.assistantCalling(
                        response.content(), response.toolCalls()));

                // ===== ④⑤ 逐个派发 + 结果过闸口 =====
                boolean writePlanProduced = false;
                boolean formRequested = false;
                for (LlmProvider.ToolCallRequest call : response.toolCalls()) {
                    AgentTool tool = registry.find(call.name()).orElse(null);

                    // 幻觉工具名：不抛异常，返回可读结果让 LLM 自我纠正
                    if (tool == null) {
                        messages.add(LlmProvider.ChatMessage.toolResult(call.id(),
                                "未知工具 " + call.name() + "，请从可用工具中选择。"));
                        continue;
                    }

                    // 权限不可见 / 只读模式 —— 与 dispatch 一致地显式拦截（这里先给出可读反馈）
                    if (!registry.visibleTo(tool, ctx)) {
                        // 只读模式与"权限不足"必须给模型不同的话术：
                        // 前者是可解除的档位，后者是账号权限，混为一谈会让用户去申请一个本来就有权限的功能
                        boolean readonly = ctx.isReadonly() && tool.writeOperation();
                        String reason = readonly ? "只读模式" : "权限不足";
                        recordToolRow(convId, ctx, streamId, tool, call, "DENIED", 0L, reason);
                        messages.add(LlmProvider.ChatMessage.toolResult(call.id(), readonly
                                ? "当前处于只读模式，不能执行写操作 " + tool.name()
                                  + "。请如实告知用户：如果需要写入数据，请把输入框的模式切到「标准模式」。"
                                : "工具 " + tool.name() + " 需要更高权限，无法为你执行。"));
                        toolCallVo.add(toolCallEntry(tool, "DENIED", 0L, reason));
                        continue;
                    }

                    int seq = publisher.nextSeq(streamId);
                    if (firstToolSeq < 0) firstToolSeq = seq;
                    emit(ctx, AgentStreamEvent.toolStart(streamId, seq, tool.name(), tool.runningLabel()));

                    long t0 = System.currentTimeMillis();
                    ToolResult result;
                    try {
                        result = registry.dispatch(tool.name(), ctx, call.arguments());
                    } catch (AccessDeniedException e) {
                        result = ToolResult.denied(e.getMessage());
                    } catch (Exception e) {
                        log.warn("灵思工具执行异常：tool={} stream={}", tool.name(), streamId, e);
                        result = ToolResult.failed(e.getMessage() == null ? "执行异常" : e.getMessage());
                    }
                    long cost = System.currentTimeMillis() - t0;

                    emit(ctx, AgentStreamEvent.toolEnd(streamId, publisher.nextSeq(streamId),
                            tool.name(), tool.label(), result.status(), cost, result.error()));
                    recordToolRow(convId, ctx, streamId, tool, call, result.status(), cost, result.error());
                    // 审计是旁路：任何异常都不得影响主链路（outcome 已产出，用户不该因为
                    // "审计写不进去"而看不到结果）
                    safeAudit(ctx, tool, call, result);

                    toolCallVo.add(toolCallEntry(tool, result.status(), cost, result.error()));
                    if (result.references() != null && !result.references().isEmpty()) {
                        allReferences.addAll(result.references());
                    }

                    // ===== ⑤.5 表单补全（D2.5 elicitation）：写类工具缺参 → 落 WAITING_INPUT → 推 INPUT_NEEDED → 收尾 =====
                    // 注意顺序：FORM 判定必须在 PLAN 之前——工具一次只返回其一
                    if (tool instanceof WriteAgentTool && result.needsInput() && result.form() != null) {
                        // LLM 首次调用的原始参数一并入库存档，表单提交后合并重跑 dry-run
                        pendingForm = taskGate.createForm(ctx, tool, result.form(), call.arguments());
                        emit(ctx, AgentStreamEvent.inputNeeded(streamId, publisher.nextSeq(streamId),
                                pendingForm));
                        formRequested = true;
                        break; // 一次只处理一张表单，避免多个弹窗让用户无所适从
                    }

                    // ===== ⑥ 写类工具：出清单 → 落 PENDING_CONFIRM → 推 PLAN_READY → 收尾 =====
                    if (tool instanceof WriteAgentTool && result.plan() != null) {
                        AgentActionPlan raw = result.plan();
                        // dry-run 阶段返回的 plan 尚未落库，这里统一走门禁落库 + 签发一次性令牌
                        AgentActionPlan persisted = taskGate.createPlan(ctx, tool,
                                raw.summary(), raw.total(), raw.items(), raw.skipped(),
                                raw.detailLines(), raw.reversible(), raw.preview());
                        pendingPlan = persisted;
                        emit(ctx, AgentStreamEvent.planReady(streamId, publisher.nextSeq(streamId),
                                persisted.toJson()));
                        writePlanProduced = true;
                        break; // 一次只处理一个待确认清单，避免多清单让用户无所适从
                    }

                    // ===== 结果过 PolicyGuard 闸口才进上下文 =====
                    // 两步分工：① tool.toLlmText 只做"裁剪"（默认 = result.llmText()，即 summary）
                    //          ② PolicyGuard 做唯一"净化"（SENSITIVE 遮罩 / RESTRICTED 拦截）
                    // ⚠️ 修复（审计 A2）：此前直接用了 ① 的结果，把 ② 的返回值丢掉，
                    //    导致 SENSITIVE 的 mask() 从未生效（restricted 因 null 判断反而正常）。
                    String text = tool.toLlmText(ctx, result);
                    String egress = policyGuard.egressText(tool.sensitivity(),
                            text == null || text.isBlank() ? result.status() : text);
                    if (egress == null) {
                        // restricted：不经 LLM，由模板渲染固定话术（D4）
                        Map<String, Object> ref = policyGuard.reference("restricted", null,
                                tool.label(), null);
                        allReferences.add(ref);
                        messages.add(LlmProvider.ChatMessage.toolResult(call.id(),
                                policyGuard.restrictedNotice(tool.label(), null)));
                    } else {
                        messages.add(LlmProvider.ChatMessage.toolResult(call.id(), egress));
                    }
                }

                if (formRequested) {
                    // 表单已弹出：本轮不再继续调 LLM，等用户在弹窗中补全后经 /tasks/{id}/input 提交
                    reply = buildFormReply(degraded);
                    break;
                }

                if (writePlanProduced) {
                    // 写清单已生成：本轮不再继续调 LLM，等用户确认
                    reply = buildPlanReply(pendingPlan, degraded);
                    break;
                }
            }

        } catch (Exception e) {
            log.error("灵思编排失败：stream={}", streamId, e);
            emit(ctx, AgentStreamEvent.error(streamId, publisher.nextSeq(streamId),
                    e.getMessage() == null ? "服务异常" : e.getMessage()));
            reply = "抱歉，处理时出现异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage())
                    + "\n\n你可以换一种问法，或稍后再试。";
        }

        // ===== ⑧ 落库 + 收官事件 =====
        int duration = (int) (System.currentTimeMillis() - startedAt);
        Sensitivity replySensitivity = pendingPlan != null ? Sensitivity.NORMAL : Sensitivity.NORMAL;
        List<Map<String, Object>> finalRefs = dedupeRefs(allReferences);
        AgentMessage saved = conversations.save(convId, AgentMessage.ROLE_ASSISTANT, reply,
                replySensitivity, conversations.summarize(reply, 100),
                toolCallVo.isEmpty() ? null : toolCallVo, finalRefs,
                streamId, providerName, duration,
                ctx.getMode(), citationsOut.isEmpty() ? null : citationsOut);
        conversations.touch(convId);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("degraded", degraded);
        extra.put("provider", providerName);
        extra.put("durationMs", duration);
        // V27：依据随收官事件一起下发，前端无需再拉一次接口
        if (!citationsOut.isEmpty()) extra.put("citations", citationsOut);
        emit(ctx, AgentStreamEvent.messageDone(streamId, publisher.nextSeq(streamId), reply, extra));

        // 组装 Controller 的返回值（契约见 业务设计方案 §3.4）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("streamId", streamId);
        out.put("conversationId", convId);
        out.put("messageId", saved.getId());
        out.put("reply", reply);
        out.put("toolCalls", toolCallVo);
        out.put("plan", pendingPlan == null ? null : pendingPlan.toJson());
        out.put("form", pendingForm);
        out.put("references", finalRefs);
        // V27：答案依据（服务端回填）。前端据此渲染依据卡；为空时前端需显式提示"未标注出处"
        out.put("citations", citationsOut);
        out.put("mode", ctx.getMode());
        out.put("provider", providerName);
        out.put("degraded", degraded);
        out.put("durationMs", duration);
        out.put("pendingTaskCount", taskGate.countPending(ctx.getUserId()));
        return out;
    }

    /* ===================================================================== 子过程 */

    /**
     * 组装对话上下文：system + 最近 K 轮。
     *
     * <p><b>⚠️ 刻意不回灌历史的 tool 消息</b>（踩过坑）。原因有二：
     * <ol>
     *   <li><b>协议层面</b>：OpenAI 兼容协议要求 {@code role="tool"} 必须带
     *       {@code tool_call_id}，且其前一条必须是带 {@code tool_calls} 的 assistant。
     *       历史的 tool 行没有配对信息（{@code agent_message} 只存摘要文本），
     *       回灌必然构造出非法请求 → DeepSeek 直接 400。</li>
     *   <li><b>语义层面</b>：工具结果是"那一轮的证据"，不是"对话内容"。
     *       跨轮回灌会让模型把过期数据当成当前事实，反而增加幻觉。</li>
     * </ol>
     * 历史只保留 user 提问与 assistant 答复——这也正是用户记得的对话。
     */
    private List<LlmProvider.ChatMessage> buildContext(Long convId, String currentMessage, ToolContext ctx,
                                                       String refsNote) {
        List<LlmProvider.ChatMessage> out = new ArrayList<>();
        out.add(LlmProvider.ChatMessage.text("system", SYSTEM_PROMPT));

        // 多轮「接续」上下文（用户反馈 item 3）：告诉模型"上一轮已经有一张待确认清单"，
        // 用户在纠正时它才会按新说法重出清单去替换，而不是只做口头说明
        //（不注入这段时模型把纠正当成全新任务，表现为"上下文偏移"）。
        String pendingNote = taskGate.pendingPlanNote(convId, ctx.getUserId());
        if (pendingNote != null) out.add(LlmProvider.ChatMessage.text("system", pendingNote));

        List<AgentMessage> history = conversations.context(convId, props.getContextRounds());
        for (AgentMessage m : history) {
            // 最后一条必然是本轮用户消息（已在 run() 里落库），跳过，避免重复
            if (isLastUserMessage(m, history, currentMessage)) continue;
            if (AgentMessage.ROLE_SYSTEM.equals(m.getRole())) continue;
            // 历史工具结果不回灌（见上方 javadoc）
            if (AgentMessage.ROLE_TOOL.equals(m.getRole())) continue;

            String role = AgentMessage.ROLE_ASSISTANT.equals(m.getRole()) ? "assistant" : "user";
            String content = m.getContent();
            if (content == null || content.isBlank()) continue;
            out.add(LlmProvider.ChatMessage.text(role, content));
        }
        // 附加上下文放在**紧贴本轮 user 消息之前**：距离越近模型越不容易忽略，
        // 且它只对这一轮有意义——历史轮次的附加引用不重复注入（历史里已有当时的文字语境）
        if (refsNote != null && !refsNote.isBlank()) {
            out.add(LlmProvider.ChatMessage.text("system", refsNote));
        }
        out.add(LlmProvider.ChatMessage.text("user", currentMessage));
        return out;
    }

    private boolean isLastUserMessage(AgentMessage m, List<AgentMessage> all, String current) {
        if (!AgentMessage.ROLE_USER.equals(m.getRole())) return false;
        if (all.isEmpty()) return false;
        AgentMessage last = all.get(all.size() - 1);
        if (last != m) return false;
        return current != null && current.equals(m.getContent());
    }

    private List<LlmProvider.ToolSpec> toSpecs(List<AgentTool> tools) {
        List<LlmProvider.ToolSpec> out = new ArrayList<>();
        for (AgentTool t : tools) {
            out.add(new LlmProvider.ToolSpec(t.name(), t.description(), t.parametersSchema()));
        }
        return out;
    }

    /**
     * 调 LLM（含重试 1 次 + 降级 + 字符流推送）。
     *
     * <p>实现已抽到 {@link AgentLlmCaller} —— 办事型（工具循环）与资料型（知识库问答）
     * 共用同一份。抽出的直接动因是消除 {@code KbPipeline} ↔ 本类的循环依赖，
     * 但即使没有它也该抽：<b>round 语义、DeltaBatch 双阈值、降级走同一套拼装</b>
     * 这三件事都在两边踩过坑，复制一份就等着行为分叉。
     *
     * <p>这里保留薄委托，让既有 4 处调用点零改动。
     */
    LlmProvider.ChatResponse callLlm(List<LlmProvider.ChatMessage> messages,
                                     List<LlmProvider.ToolSpec> specs,
                                     ToolContext ctx, String streamId, int round) {
        return llmCaller.callLlm(messages, specs, ctx, streamId, round);
    }

    /** 表单补全弹出后的固定话术（用户看到的是表单卡 + 这句话） */
    private String buildFormReply(boolean degraded) {
        StringBuilder sb = new StringBuilder();
        sb.append("要完成这一步还缺少一些关键信息，我已为你弹出**信息补充表单**。\n\n");
        sb.append("你提到过的内容已预填进去，请核对并补全后提交——提交后我会生成待确认清单，确认后才会真正执行。");
        if (degraded) {
            sb.append("\n\n（当前为规则模式，请仔细核对表单内容。）");
        }
        return sb.toString();
    }

    /** 写清单生成后的固定话术（用户看到的是清单卡 + 这句话） */
    private String buildPlanReply(AgentActionPlan plan, boolean degraded) {
        StringBuilder sb = new StringBuilder();
        sb.append("我整理了待执行的清单，共 **").append(plan.total()).append("** 项。\n\n");
        if (plan.skipped() != null && !plan.skipped().isEmpty()) {
            sb.append("其中 **").append(plan.skipped().size()).append("** 项无法执行，已在清单中标注原因。\n\n");
        }
        sb.append(plan.reversible()
                ? "确认后立即执行；若执行有误，可以在台账里按批次撤回。"
                : "确认后立即执行。此操作不可自动撤回。");
        if (degraded) {
            sb.append("\n\n（当前为规则模式，清单由关键词匹配生成，请仔细核对。）");
        }
        return sb.toString();
    }

    private Map<String, Object> toolCallEntry(AgentTool tool, String status, long cost, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool.name());
        m.put("label", tool.label());
        m.put("status", status);
        m.put("durationMs", cost);
        m.put("write", tool.writeOperation());
        m.put("sensitivity", tool.sensitivity().name().toLowerCase());
        if (error != null) m.put("error", error);
        return m;
    }

    /**
     * 审计调用兜底壳（D9）。
     *
     * <p>为什么要单独包一层：审计用 {@code REQUIRES_NEW}，其异常可能在服务代理的
     * <b>commit 阶段</b>抛出（{@code UnexpectedRollbackException}），
     * 位置在 {@code AuditService} 方法体的 try/catch <b>之外</b>。
     * 只有这里再兜一次，才能保证"审计挂了也不影响用户拿到结果"这条硬约束。
     */
    private void safeAudit(ToolContext ctx, AgentTool tool, LlmProvider.ToolCallRequest call, ToolResult result) {
        try {
            // ⚠️ 取值必须 ≤10 字符：agent_audit_log.side_effect 是 VARCHAR(10)（V24 契约 READ/WRITE）。
            // 曾写成 "WRITE_PREVIEW"（13 字符）→ 插入报 Data truncation → 审计事务被标 rollback-only →
            // 代理 commit 抛 UnexpectedRollbackException → 被本方法兜掉（只留一条 WARN）。
            // 后果是"所有写类工具的 dry-run 都没有审计行"，而日志只提示"旁路异常已忽略"，极易漏诊。
            auditService.log(ctx, tool.name(), call.arguments(),
                    tool.writeOperation() ? "PREVIEW" : "READ",
                    tool.sensitivity(), result.status());
        } catch (Exception e) {
            log.warn("灵思审计旁路异常已忽略：tool={} err={}", tool.name(), e.getMessage());
        }
    }

    private void recordToolRow(Long convId, ToolContext ctx, String streamId, AgentTool tool,
                               LlmProvider.ToolCallRequest call, String status, long cost, String error) {
        publisher.recordToolCall(convId, null, streamId, publisher.nextSeq(streamId),
                ctx.getUserId(), tool.name(), tool.label(),
                auditService.toJson(call.arguments()),
                tool.sensitivity().name().toLowerCase(),
                tool.writeOperation() ? "WRITE" : "READ",
                cost, status, error);
    }

    private List<Map<String, Object>> dedupeRefs(List<Map<String, Object>> refs) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> r : refs) {
            String key = String.valueOf(r.get("type")) + "#" + String.valueOf(r.get("id"))
                    + "#" + String.valueOf(r.get("label"));
            seen.putIfAbsent(key, r);
        }
        return new ArrayList<>(seen.values());
    }

    /** 推事件（用户定向队列）。推送失败不阻断主链路。包级可见：KbPipeline 复用 */
    void emit(ToolContext ctx, AgentStreamEvent event) {
        if (ctx.getLoginUser() == null) return;
        publisher.publish(ctx.getLoginUser().getUsername(), event);
    }
}
