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
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.orchestration.Blackboard;
import com.pams.module.agent.orchestration.CollaborationRouter;
import com.pams.module.agent.orchestration.CoordinatorAgent;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import com.pams.module.agent.stream.ThinkingTrace;
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


    @org.springframework.beans.factory.annotation.Autowired private AgentRequestService requests;
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.repository.AgentMessageRepository messageRows;
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
    /** 协作路由器（标准模式下判定是否升级为多 Agent 协作） */
    private final CollaborationRouter collaborationRouter;
    /** 协调者（升级为协作时执行协作 Run；与 AgentTeamController 共用同一实现） */
    private final CoordinatorAgent coordinatorAgent;
    /** 思维链暂存：推理是流式片段，落库发生在收官段，中间靠它按 streamId 攒起来（V35） */
    private final ThinkingTrace thinkingTrace;
    private final ToolOutcomeHandler outcomeHandler;
    private final ContextAssembler contextAssembler;
    @org.springframework.beans.factory.annotation.Autowired
    private LongTermMemory longTermMemory;

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
                             KbPipeline kbPipeline,
                             CollaborationRouter collaborationRouter,
                             CoordinatorAgent coordinatorAgent,
                             ThinkingTrace thinkingTrace, ContextAssembler contextAssembler) {
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
        this.collaborationRouter = collaborationRouter;
        this.coordinatorAgent = coordinatorAgent;
        this.thinkingTrace = thinkingTrace;
        this.contextAssembler = contextAssembler;
        this.outcomeHandler = new ToolOutcomeHandler(taskGate, publisher);
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
        return managed(message,conversationId,ctx,streamId,refs,true);
    }

    /**
     * 带「自动协作开关」的问答（C 批次协作内聚）。
     *
     * <p>{@code collabEnabled=false} 时标准模式也不会升级为协作 —— 这是用户在输入框上的否决权。
     * 其余入口（{@link #chatSync} 的两参/四参重载）默认开启。
     */
    public Map<String, Object> chatSync(String message, Long conversationId, ToolContext ctx,
                                        List<Map<String, Object>> refs, boolean collabEnabled) {
        String streamId = publisher.newStream("st", ctx == null ? null : ctx.getUserId());
        return managed(message,conversationId,ctx,streamId,refs,collabEnabled);
    }

    /** 发起异步编排（用于显式要求后台执行的场景），立即返回 streamId */
    public String chatAsync(String message, Long conversationId, ToolContext ctx) {
        String streamId = publisher.newStream("st", ctx == null ? null : ctx.getUserId());
        agentExecutor.execute(() -> {
            try {
                managed(message, conversationId, ctx, streamId, List.of(), true);
            } catch (Exception e) {
                log.error("灵思异步编排失败：stream={}", streamId, e);
            }
        });
        return streamId;
    }

    /* ===================================================================== 主流程 */
    private Map<String,Object> managed(String message,Long conversationId,ToolContext ctx,String streamId,List<Map<String,Object>> refs,boolean collab) {
        ToolContext bound=publisher.bind(streamId,ctx);
        try {
            if(publisher.isAborted(streamId)) throw new java.util.concurrent.CancellationException();
            return run(message,conversationId,bound,streamId,refs,collab);
        } catch(java.util.concurrent.CancellationException cancelled) {
            String reply="本次生成已停止。已提交的业务动作请查看执行结果。";
            var request=requests==null?null:requests.byStream(streamId).orElse(null);
            Long actualConversation=request==null?conversationId:request.getConversationId();
            Long messageId=null;
            if(actualConversation!=null && messageRows!=null) {
                var existing=messageRows.findByStreamIdAndRole(streamId,AgentMessage.ROLE_ASSISTANT);
                if(existing.isEmpty()) {
                    var saved=conversations.save(actualConversation,AgentMessage.ROLE_ASSISTANT,reply,Sensitivity.NORMAL,reply,null,List.of(),streamId,"",0,bound.getMode(),null);
                    saved.setRunId(request==null?null:request.getRunId());conversations.saveExecutionState(saved,"CANCELLED");messageId=saved.getId();
                    conversations.touch(actualConversation);
                } else messageId=existing.getLast().getId();
            }
            emit(bound,AgentStreamEvent.aborted(streamId,publisher.nextSeq(streamId)));
            var out=new LinkedHashMap<String,Object>(); out.put("requestId",bound.getRequestId());out.put("streamId",streamId);
            out.put("conversationId",actualConversation);out.put("messageId",messageId);out.put("executionState","CANCELLED");out.put("reply",reply);return out;
        } catch(RuntimeException failure) {
            emit(bound,AgentStreamEvent.error(streamId,publisher.nextSeq(streamId),"请求未完成，请恢复请求状态后重试。"));throw failure;
        } finally { thinkingTrace.clear(streamId);publisher.clear(streamId);llmCaller.endTask(streamId); }
    }

    private Map<String, Object> run(String message, Long conversationId, ToolContext ctx, String streamId,
                                    List<Map<String, Object>> refs, boolean collabEnabled) {
        long startedAt = System.currentTimeMillis();
        llmCaller.beginTask(streamId, ctx);

        // ===== ① TTFE：进入编排的第一时间就推 THINKING，不得等首个工具出结果 =====
        emitNote(ctx, streamId, "正在理解你的问题…");

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
        publisher.identify(ctx);
        if(publisher.isAborted(streamId)) throw new java.util.concurrent.CancellationException();

        // 用户消息落库（写时脱敏由 ConversationStore 内部完成）
        // 附加引用随消息落 refs_json：历史里能看到"当时选了哪个活动"，而不只是文字里的一个代词
        // ⚠️ ctx 一并传入：附加上下文过去是"直取 ID、无权限校验"的旁路（权限审计 A1）
        ChatRefsResolver.Resolved attached = refsResolver.resolve(refs, ctx);
        AgentMessage userMessage = conversations.save(convId, AgentMessage.ROLE_USER, message, Sensitivity.NORMAL,
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
        // 本轮留存档位：取所有**已执行**工具里最严的一档（见 Sensitivity.max 的说明）。
        // 只算已执行的，不算"未可见/被拒"的：那些没有任何数据进入上下文，不该抬高留存档位。
        Sensitivity turnSensitivity = Sensitivity.NORMAL;

        // ===== 模式分流（方案 §7.3）=====
        // 「对话」档位走独立流水线，**不进工具循环**。
        // 这么写（而不是把整个 try 包进 if/else 再缩进）是为了让既有办事型代码**零改动** ——
        // 那段循环里有 6 处踩过坑的细节（round 语义、DENIED 话术分流、plan 短路等），
        // 大范围缩进会产生一个几乎无法 review 的 diff。
        String memoryReply=longTermMemory.command(userMessage,ctx);
        boolean memoryHandled=memoryReply!=null;
        if(memoryHandled){reply=memoryReply;providerName="memory";}
        boolean kbMode = ctx.isKb() && !memoryHandled;
        if (kbMode) {
            KbPipeline.KbOutcome kb = kbPipeline.run(message, convId, ctx, streamId);
            citationsOut.addAll(kb.citations());
            reply = kb.reply();
            if (kb.provider() != null) providerName = kb.provider();
            degraded = kb.degraded();
        }

        // ===== 多 Agent 协作分流（C 批次：协作内聚进灵思）=====
        // 标准模式下由路由器判定是否升级；命中则整轮交给协调者执行，**不再进单 Agent 工具循环**。
        // 与 kbMode 同样是"前置分流"而非嵌进循环 —— 单 Agent 那段代码保持零改动。
        //
        // 协作与单 Agent 共用**同一条流**（streamId）与同一套事件：
        // 前端因此不需要知道"这次是协作"，过程区自然就把专家进度与审查结论显示出来了。
        boolean collabMode = false;
        String collaborationState = memoryHandled ? memoryReply.startsWith("未更改长期记忆：") ? "FAILED" : "SUCCESS" : null;
        Long collaborationRunId = null;
        if (!kbMode && !memoryHandled) {
            CollaborationRouter.Decision decision =
                    collaborationRouter.route(message, ctx.getMode(), collabEnabled);
            if (decision.upgrade()) {
                collabMode = true;
                String context = attached.note();
                CollaborationOutcome collaboration = runCollaboration(decision, message, convId, ctx, streamId, context);
                reply = collaboration.reply();
                collaborationState = collaboration.state();
                collaborationRunId = collaboration.runId();
                if (collaboration.blackboard() != null) {
                    for (var result : collaboration.blackboard().getResults()) {
                        if (result.plan() != null) pendingPlan = result.plan();
                        if (result.form() != null) pendingForm = result.form();
                        toolCallVo.addAll(result.toolCalls()); allReferences.addAll(result.references());
                        turnSensitivity = Sensitivity.max(turnSensitivity,result.sensitivity());
                        degraded |= result.degraded();
                        if (result.provider()!=null && !result.provider().isBlank()) providerName = result.provider();
                    }
                }
            }
        }

        if (!kbMode && !collabMode && !memoryHandled)
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
                    throw new java.util.concurrent.CancellationException();
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

                // Preserve native output state across tool exchanges.
                messages.add(LlmProvider.ChatMessage.assistantCalling(response));

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
                        toolCallVo.add(toolCallEntry(tool, "DENIED", 0L, reason, call.arguments()));
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

                    // 留存档位取本轮最严：碰过敏感数据的轮次，答复与思维链都不再按 NORMAL 全量留存
                    turnSensitivity = Sensitivity.max(turnSensitivity, tool.sensitivity());

                    emit(ctx, AgentStreamEvent.toolEnd(streamId, publisher.nextSeq(streamId),
                            tool.name(), tool.label(), result.status(), cost, result.error()));
                    recordToolRow(convId, ctx, streamId, tool, call, result.status(), cost, result.error());
                    // 审计是旁路：任何异常都不得影响主链路（outcome 已产出，用户不该因为
                    // "审计写不进去"而看不到结果）
                    safeAudit(ctx, tool, call, result);

                    toolCallVo.add(toolCallEntry(tool, result.status(), cost, result.error(), call.arguments()));
                    if (result.references() != null && !result.references().isEmpty()) {
                        allReferences.addAll(result.references());
                    }

                    if(publisher.isAborted(streamId)) throw new java.util.concurrent.CancellationException();
                    // Both execution strategies share the same action persistence and event contract.
                    ToolOutcomeHandler.PendingAction action = outcomeHandler.prepare(ctx,tool,result,call);
                    if (action.form() != null) {
                        pendingForm = action.form(); formRequested = true; break;
                    }
                    if (action.plan() != null) {
                        pendingPlan = action.plan(); writePlanProduced = true; break;
                    }

                    // ===== 结果过 PolicyGuard 闸口才进上下文 =====
                    // 两步分工：① tool.toLlmText 只做裁剪（普通读结果带有界事实，敏感/写结果只带摘要）
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

        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled;
        } catch (Exception e) {
            log.error("灵思编排失败：stream={}", streamId, e);
            emit(ctx, AgentStreamEvent.error(streamId, publisher.nextSeq(streamId),
                    e.getMessage() == null ? "服务异常" : e.getMessage()));
            reply = "抱歉，处理时出现异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage())
                    + "\n\n你可以换一种问法，或稍后再试。";
        }

        // ===== ⑧ 落库 + 收官事件 =====
        int duration = (int) (System.currentTimeMillis() - startedAt);
        // 该轮的留存档位 = 本轮最严的工具档位（D4 / 决策 6）。
        // ⚠️ 此前这里写的是 `pendingPlan != null ? NORMAL : NORMAL`——两个分支同值，
        // 等于敏感轮次的答复一直在按全量落库（策略写了但从未生效）。
        Sensitivity replySensitivity = turnSensitivity;
        List<Map<String, Object>> finalRefs = dedupeRefs(allReferences);
        // V35：思维链随 assistant 消息落库，历史回看才看得到"为什么这么做"。
        // 取快照后即释放内存副本（toJson 为空时返回 null，老链路行为不变）
        List<Map<String, Object>> thinkingVo = thinkingTrace.toList(streamId);
        String thinkingJson = thinkingVo.isEmpty() ? null : thinkingTrace.toJson(streamId);
        // V36：编排旁白（「正在理解你的问题…」这类）与推理分段一起落库 ——
        // 只落推理的话，刷新后过程区会缺掉旁白行，与实时看到的时间轴不是同一条
        List<Map<String, Object>> thinkingNotesVo = thinkingTrace.notesList(streamId);
        String thinkingNotesJson = thinkingNotesVo.isEmpty() ? null : thinkingTrace.notesJson(streamId);
        AgentMessage saved = conversations.save(convId, AgentMessage.ROLE_ASSISTANT, reply,
                replySensitivity, conversations.summarize(reply, 100),
                toolCallVo.isEmpty() ? null : toolCallVo, finalRefs,
                streamId, providerName, duration,
                ctx.getMode(), citationsOut.isEmpty() ? null : citationsOut,
                thinkingJson, thinkingNotesJson);
        saved.setRunId(collaborationRunId);
        conversations.saveExecutionState(saved, collaborationState);
        if (collabMode && pendingPlan != null) emit(ctx,AgentStreamEvent.planReady(streamId,publisher.nextSeq(streamId),pendingPlan.toJson()));
        if (collabMode && pendingForm != null) emit(ctx,AgentStreamEvent.inputNeeded(streamId,publisher.nextSeq(streamId),pendingForm));
        thinkingTrace.clear(streamId);
        conversations.touch(convId);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("degraded", degraded);
        extra.put("provider", providerName);
        extra.put("executionState", collaborationState);
        extra.put("durationMs", duration);
        // V27：依据随收官事件一起下发，前端无需再拉一次接口
        if (!citationsOut.isEmpty()) extra.put("citations", citationsOut);
        emit(ctx, AgentStreamEvent.messageDone(streamId, publisher.nextSeq(streamId), reply, extra));

        // 组装 Controller 的返回值（契约见 业务设计方案 §3.4）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("streamId", streamId);
        out.put("requestId",ctx.getRequestId());
        out.put("conversationId", convId);
        out.put("messageId", saved.getId());
        out.put("reply", reply);
        out.put("toolCalls", toolCallVo);
        out.put("plan", pendingPlan == null ? null : pendingPlan.toJson());
        out.put("form", pendingForm);
        out.put("executionState", collaborationState);
        if (collaborationRunId!=null) out.put("run",dagScheduler.view(collaborationRunId,ctx));
        out.put("references", finalRefs);
        // V27：答案依据（服务端回填）。前端据此渲染依据卡；为空时前端需显式提示"未标注出处"
        out.put("citations", citationsOut);
        // V35：思维链（与本轮落库的是同一份快照）。前端把本轮消息存下来后，
        // 下一轮开始时它变成"历史轮次"，靠这份数据回放出思考段 —— 不必再拉一次接口。
        out.put("thinking", thinkingVo);
        // V36：编排旁白（与落库同一份快照）。前端把它按 ts 排进时间轴，
        // 于是"刷新前后看到的是同一条过程"这句话对旁白也成立
        out.put("thinkingNotes", thinkingNotesVo);
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
     *       回灌会构造出缺少配对调用状态的非法请求。</li>
     *   <li><b>语义层面</b>：工具结果是"那一轮的证据"，不是"对话内容"。
     *       跨轮回灌会让模型把过期数据当成当前事实，反而增加幻觉。</li>
     * </ol>
     * 历史只保留 user 提问与 assistant 答复——这也正是用户记得的对话。
     */
    private List<LlmProvider.ChatMessage> buildContext(Long convId, String currentMessage, ToolContext ctx,
                                                       String refsNote) {
        return contextAssembler.assemble(com.pams.module.agent.prompt.PromptRenderer.executor(ctx, registry.visibleTools(ctx), null, props.getMaxToolIterations()), convId, currentMessage, ctx, refsNote);
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

    /**
     * 工具调用摘要（进 {@code toolCalls[]}：/chat 响应 + 消息落库，历史回放都读它）。
     *
     * @param args 本次调用的原始入参。<b>写入侧不做判断</b> —— 是否下发给前端由
     *             {@link PolicyGuard#egressArgs} 这道读侧闸门决定（normal 才给遮罩明文），
     *             因为 audit 的 {@code args_json} 是审计口径，不能为了展示去改它。
     */
    private Map<String, Object> toolCallEntry(AgentTool tool, String status, long cost, String error,
                                              Map<String, Object> args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool.name());
        m.put("label", tool.label());
        m.put("status", status);
        m.put("durationMs", cost);
        m.put("write", tool.writeOperation());
        m.put("sensitivity", tool.sensitivity().name().toLowerCase());
        if (error != null) m.put("error", error);
        // V36：入参下钻。sensitive/restricted → null（前端见到 null 就不给下钻入口）
        m.put("argsJson", policyGuard.egressArgs(tool.sensitivity(), auditService.toJson(args)));
        // 工具行的**起始**时间戳（epoch ms）：前端据此把工具行与推理段/旁白行按时间交错。
        // 用 now-cost 而不是落库时刻：cost 是执行耗时，落库发生在结束之后，
        // 用结束时刻排序会让"耗时长的工具"整行漂到它后面的推理段之后（顺序读起来是错的）。
        // 现在还没有 start 时间落库，这是不引入新列的前提下最接近真实位置的值。
        m.put("ts", System.currentTimeMillis() - Math.max(0L, cost));
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

    /**
     * 推一条编排旁白（{@code THINKING}）并**同时收进 {@link ThinkingTrace}**。
     *
     * <p>顺序是刻意的：先收集再发送 —— 反过来的话，事件已经推到前端而落库快照里没有，
     * 刷新后用户看到的时间轴就会少一行「刚刚明明看到过」的旁白。
     * 收集本身不会抛异常，所以"先收后发"不会让实时性受损。
     */
    void emitNote(ToolContext ctx, String streamId, String note) {
        thinkingTrace.note(streamId, note);
        emit(ctx, AgentStreamEvent.thinking(streamId, publisher.nextSeq(streamId), note));
    }

    /**
     * 执行一次协作 Run 并组装成给用户的回复（C 批次）。
     *
     * <p>协作复用**当前会话的 streamId**，不另开一条流 —— 这样专家进度、委派边、审查结论
     * 与单 Agent 的工具行落在同一条时间轴上，前端无需区分两种执行策略。
     *
     * <p>失败一律降级为可读文案而不抛异常：协作是增强路径，不能因为它出错就让对话不可用。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.agent.orchestration.DagScheduler dagScheduler;
    private record CollaborationOutcome(String reply, Blackboard blackboard, String state, Long runId) {}

    private CollaborationOutcome runCollaboration(CollaborationRouter.Decision decision, String message, Long convId,
                                    ToolContext ctx, String streamId, String context) {
        // 先声明判定结果：否则用户会看到专家行突然出现却不知道原因
        emit(ctx, AgentStreamEvent.collabDecision(
                streamId, publisher.nextSeq(streamId), convId, decision.mode(), decision.reason()));

        AgentRun run = null;
        try {
            // 用 runWithBlackboard：既落库 Run，又把黑板拿回来组织回复
            long t0 = System.currentTimeMillis();
            CoordinatorAgent.RunOutcome outcome =
                    coordinatorAgent.runWithBlackboard(message, ctx, convId, decision.mode(), streamId, context);
            run = outcome.run();
            Blackboard bb = outcome.blackboard();
            log.info("协作完成：runId={} mode={} 专家={} 耗时={}ms",
                    run == null ? null : run.getId(), decision.mode(), decision.experts(),
                    System.currentTimeMillis() - t0);
            return new CollaborationOutcome(composeCollabReply(bb, decision),bb,run.getStatus(),run.getId());
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled;
        } catch (Exception e) {
            log.warn("协作执行失败，本轮不产出结果：runId={}",
                    run == null ? null : run.getId(), e);
            return new CollaborationOutcome("这次协作没能完成，请查看执行结果或稍后重试。",null,"FAILED",run==null?null:run.getId());
        }
    }

    /**
     * 把黑板整理成用户可读的回复。
     *
     * <p>刻意做成「按专家分段的要点」而不是把黑板原样倒出来：
     * 黑板是给系统看的工作记忆（findings/artifacts/decisions 三套结构），
     * 直接序列化会把内部字段名暴露给用户。
     */
    private String composeCollabReply(Blackboard bb, CollaborationRouter.Decision decision) {
        StringBuilder sb = new StringBuilder();
        String state = bb == null ? "FAILED" : bb.executionState();
        sb.append(switch (state) {
            case "WAITING_INPUT" -> "请补齐本步骤的信息，再继续处理。";
            case "WAITING_CONFIRM" -> "已生成待确认预览，确认后才会写入。依赖该结果的步骤会在确认后继续。";
            case "CANCELLED" -> "本次执行已停止。";
            case "PARTIAL" -> "已保留本次结果，仍有未完成或待核对的事项。";
            case "SUCCESS" -> "本轮任务已完成。";
            default -> "本轮任务尚未完成，请查看执行结果。";
        }).append('\n');

        if (bb == null) {
            return sb.toString();
        }

        List<Blackboard.Finding> findings = bb.getFindings();
        if (findings != null && !findings.isEmpty()) {
            sb.append("\n**结果要点**\n");
            for (Blackboard.Finding f : findings) {
                // 协调者自己的规划说明不进正文（它不是"结果"）
                if (AgentCatalog.COORDINATOR.equals(f.getAgent())) continue;
                String summary = f.getSummary() == null ? "" : f.getSummary().trim();
                if (summary.isEmpty()) continue;
                sb.append("- **").append(AgentCatalog.displayName(f.getAgent())).append("**：")
                        .append(summary).append('\n');
            }
        }

        List<Blackboard.Decision> decisions = bb.getDecisions();
        if (decisions != null && !decisions.isEmpty()) {
            sb.append("\n**审查结论**\n");
            for (Blackboard.Decision d : decisions) {
                boolean pass = "PASS".equalsIgnoreCase(d.getVerdict());
                sb.append("- ").append(pass ? "✅ 通过" : "↻ 需修订")
                        .append("：").append(d.getReason() == null ? "—" : d.getReason()).append('\n');
            }
        }

        return sb.toString().trim();
    }
}
