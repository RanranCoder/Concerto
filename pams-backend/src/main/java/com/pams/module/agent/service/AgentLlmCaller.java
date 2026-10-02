package com.pams.module.agent.service;

import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 一次 LLM 调用（含重试 1 次 + 降级 + 字符流推送）。
 *
 * <h3>为什么从编排器里抽出来</h3>
 * 两条流水线都需要它：办事型（工具循环，{@code AgentOrchestrator}）
 * 与资料型（知识库问答，{@code KbPipeline}）。抽出来的直接动因是**消除循环依赖**
 * （KbPipeline 需要它、编排器又需要 KbPipeline），但即使没有这个动因也该抽：
 * 这里的三件事都踩过坑，**复制一份就等于埋一个"两条链路行为分叉"的隐患**：
 * <ol>
 *   <li><b>round 语义</b>：重试 → {@code round+1000}，降级 → {@code round+2000}。
 *       前端"遇 round 变化即清空已拼装回复"，所以每次"重新生成一段文本"都必须换 round。</li>
 *   <li><b>DeltaBatch 双阈值合并</b>：长度 24 字符 或 60ms。一个 token 一条 WS 消息
 *       会把 STOMP 连接与前端渲染都打爆。</li>
 *   <li><b>降级也走同一套拼装逻辑</b>：用 {@link #emitWhole} 把整段文本当成
 *       "一个 round 只有一条 delta"，而不是给降级单独写渲染分支。</li>
 * </ol>
 */
@Slf4j
@Service
public class AgentLlmCaller {

    private final LlmProvider primaryProvider;
    private final LlmProvider stubProvider;
    private final AgentProperties props;
    private final AgentEventPublisher publisher;

    public AgentLlmCaller(LlmProvider primaryProvider,
                          @Qualifier("stubProvider") LlmProvider stubProvider,
                          AgentProperties props,
                          AgentEventPublisher publisher) {
        this.primaryProvider = primaryProvider;
        this.stubProvider = stubProvider;
        this.props = props;
        this.publisher = publisher;
    }

    /**
     * 调 LLM。
     *
     * @param specs 工具清单；**知识库问答传空列表** —— 空 specs 意味着模型不会产出 tool_calls，
     *              这正是"对话档位没有工具"的物理体现，而不是靠 prompt 请求它别调
     * @param round 本轮序号（从 0 起）；调用方只在"需要重新生成一段文本"时才递增
     */
    public LlmProvider.ChatResponse callLlm(List<LlmProvider.ChatMessage> messages,
                                           List<LlmProvider.ToolSpec> specs,
                                           ToolContext ctx, String streamId, int round) {
        LlmProvider.ChatRequest req = new LlmProvider.ChatRequest(
                messages, specs, props.getTemperature(), props.getMaxTokens());

        // 已经是 stub（未配 Key 或显式配置）→ 直连规则模式
        if ("stub".equals(primaryProvider.name())) {
            return emitWhole(safeStub(req), ctx, streamId, round + 2000);
        }

        DeltaBatch batch = new DeltaBatch(ctx, streamId, round);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (attempt == 2) {
                    // 上一次可能已经吐了半截文本：换个 round，前端自然从头拼
                    batch = new DeltaBatch(ctx, streamId, round + 1000);
                }
                LlmProvider.ChatResponse resp = primaryProvider.chatStream(req, batch::accept);
                batch.flush();
                return resp;
            } catch (Exception e) {
                batch.discard();
                if (attempt == 1) {
                    log.warn("灵思主 provider 调用失败，重试 1 次：{}", e.getMessage());
                } else {
                    log.warn("灵思主 provider 重试仍失败，降级为规则模式：{}", e.getMessage());
                }
            }
        }
        return emitWhole(safeStub(req), ctx, streamId, round + 2000);
    }

    /**
     * 非流式结果的整段推送（降级路径用）：语义上等价于"一个 round 只有一条 delta"，
     * 让降级回复也走同一套前端拼装逻辑，而不是给降级单独写一条渲染分支。
     */
    LlmProvider.ChatResponse emitWhole(LlmProvider.ChatResponse resp, ToolContext ctx,
                                       String streamId, int round) {
        String text = resp.content();
        if (text != null && !text.isEmpty()) {
            emit(ctx, AgentStreamEvent.messageDelta(streamId, publisher.nextSeq(streamId), round, text));
        }
        return resp;
    }

    /**
     * 增量合并器：长度 + 时间双阈值。
     *
     * <p>短而快的时候攒着，长或慢就立刻发，体感延迟控制在几十毫秒内。
     */
    private class DeltaBatch {
        private static final int MAX_CHARS = 24;
        private static final long MAX_MS = 60;

        private final ToolContext ctx;
        private final String streamId;
        private final int round;
        private final StringBuilder buf = new StringBuilder();
        private long lastFlush = System.currentTimeMillis();
        private boolean discarded;

        DeltaBatch(ToolContext ctx, String streamId, int round) {
            this.ctx = ctx;
            this.streamId = streamId;
            this.round = round;
        }

        void accept(String piece) {
            if (discarded || piece == null || piece.isEmpty()) return;
            buf.append(piece);
            if (buf.length() >= MAX_CHARS || System.currentTimeMillis() - lastFlush >= MAX_MS) flush();
        }

        void flush() {
            if (discarded || buf.length() == 0) return;
            emit(ctx, AgentStreamEvent.messageDelta(streamId, publisher.nextSeq(streamId), round, buf.toString()));
            buf.setLength(0);
            lastFlush = System.currentTimeMillis();
        }

        /** 调用失败：已攒的片段作废（下一个 round 会重发完整文本） */
        void discard() {
            discarded = true;
            buf.setLength(0);
        }
    }

    private LlmProvider.ChatResponse safeStub(LlmProvider.ChatRequest req) {
        try {
            LlmProvider.ChatResponse r = stubProvider.chat(req);
            if (r.degraded()) return r;
            return new LlmProvider.ChatResponse(r.content(), r.toolCalls(), "stub", true);
        } catch (Exception e) {
            log.error("灵思兜底 provider 也失败", e);
            return LlmProvider.ChatResponse.stub(
                    "当前无法连接到智能服务，请稍后再试。你仍可正常使用系统中的各项业务功能。");
        }
    }

    /** 推事件（用户定向队列）。推送失败不阻断主链路 */
    private void emit(ToolContext ctx, AgentStreamEvent event) {
        if (ctx == null || ctx.getLoginUser() == null) return;
        publisher.publish(ctx.getLoginUser().getUsername(), event);
    }
}
