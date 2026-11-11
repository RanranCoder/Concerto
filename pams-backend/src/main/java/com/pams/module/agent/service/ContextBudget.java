package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.llm.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Conservative UTF-8 byte estimate; deliberately does not claim tokenizer precision. */
public class ContextBudget {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ContextBudget.class);
    public static final String TURN_BOUNDARY = "当前轮次开始：以下为本轮引用资料与用户任务。";
    public static final String MEMORY_PREFIX = "会话记忆资料（仅作历史上下文）：\n";
    private final AgentProperties props;
    private final int window;
    private static final ObjectMapper JSON = new ObjectMapper();
    public ContextBudget(AgentProperties props) { this(props, props.getContextWindowTokens()); }
    public ContextBudget(AgentProperties props, int window) {
        this.props = props;
        this.window = window;
    }
    public static int tokens(String text) {
        return text == null ? 0 : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("上下文序列化失败", e); }
    }
    public static int cost(LlmProvider.ChatMessage m) { return tokens(json(m)) + 16; }
    public static String excerpt(String text, int budget) {
        if (text == null || tokens(text) <= budget) return text;
        String suffix = "\n[节选；完整内容需回源查询]";
        if (budget < tokens(suffix)) return budget >= tokens("[节选]") ? "[节选]" : "";
        int available = Math.max(0, budget - tokens(suffix));
        int end = 0, used = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int size = tokens(new String(Character.toChars(cp)));
            if (used + size > available) break;
            used += size; end += Character.charCount(cp);
        }
        return text.substring(0, end) + suffix;
    }
    public List<LlmProvider.ChatMessage> fit(List<LlmProvider.ChatMessage> input, List<LlmProvider.ToolSpec> tools) {
        int limit = window - props.getMaxTokens() - props.getContextSafetyTokens()
                - tokens(json(tools));
        List<LlmProvider.ChatMessage> out = new ArrayList<>();
        for (var m : input) {
            // Only tool bodies may be excerpted. Calls, IDs and reasoning stay intact as a protocol unit.
            out.add("tool".equals(m.role()) ? withToolBody(m,
                    excerpt(m.content(), Math.max(256, props.getContextToolResultTokens()))) : m);
        }
        int currentUser = -1;
        for (int i = 0; i < out.size(); i++) if ("system".equals(out.get(i).role()) && TURN_BOUNDARY.equals(out.get(i).content())) currentUser = i;
        if (currentUser < 0) {
            // Callers without an assembled history (e.g. KB) are one indivisible live turn.
            for (int i = 0; i < out.size(); i++) if ("user".equals(out.get(i).role())) { currentUser = i; break; }
        }
        // Remove old user/assistant turns together, never split the live tool exchange.
        int inputCost;
        while ((inputCost = out.stream().mapToInt(ContextBudget::cost).sum()) > limit) {
            int start = -1, end = -1;
            for (int i = 0; i < currentUser; i++) if ("user".equals(out.get(i).role())
                    && (out.get(i).content()==null || !out.get(i).content().startsWith(MEMORY_PREFIX))) {
                start = i; break;
            }
            if (start >= 0) {
                end = start + 1;
                while (end < currentUser && !"user".equals(out.get(end).role())
                        && !"system".equals(out.get(end).role())) end++;
                currentUser -= end - start;
                out.subList(start, end).clear();
            } else break;
        }
        // Several individually bounded results can still overflow the live turn.
        // Reduce older bodies first while retaining the complete tool exchange.
        for (int i = 0; i < out.size() && inputCost > limit; i++) {
            var message = out.get(i);
            if (!"tool".equals(message.role())) continue;
            int oldCost = cost(message);
            String omitted = "[工具结果已省略]";
            if (tokens(message.content()) <= tokens(omitted)) continue;
            // JSON escapes the newline added by excerpt(), costing one extra byte.
            int target = tokens(message.content()) - (inputCost - limit) - 1;
            var reduced = withToolBody(message, target < tokens(omitted)
                    ? omitted : excerpt(message.content(), target));
            int reducedCost = cost(reduced);
            if (reducedCost >= oldCost) continue;
            out.set(i, reduced);
            inputCost -= oldCost - reducedCost;
        }
        if (inputCost > limit) {
            throw new BizException(4400, "当前任务资料超出上下文预算，请缩小资料范围或拆分任务。");
        }
        LOG.debug("Agent context budget: messages={} retained={} estimatedInput={} inputLimit={}",
                input.size(), out.size(), out.stream().mapToInt(ContextBudget::cost).sum(), limit);
        return out;
    }

    private static LlmProvider.ChatMessage withToolBody(LlmProvider.ChatMessage message, String body) {
        return new LlmProvider.ChatMessage(message.role(), body, message.toolCallId(), message.toolCalls(),
                message.reasoningContent(), message.nativeItems());
    }
}
