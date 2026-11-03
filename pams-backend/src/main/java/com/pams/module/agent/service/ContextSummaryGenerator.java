package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.llm.LlmProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** Isolated call: no tools, no shared answer deltas, no recursive context assembly. */
@Service
public class ContextSummaryGenerator {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AgentContextMetrics metrics;
    private final AgentLlmCaller caller;
    private final ObjectMapper json = new ObjectMapper();
    public ContextSummaryGenerator(@Lazy AgentLlmCaller caller) { this.caller = caller; }
    public String generate(List<ConversationMemory.Item> previous, List<ConversationMemory.Source> added,
                           String streamId, int budget) {
        String payload;
        try { payload = json.writeValueAsString(Map.of("previous", previous, "added", added, "outputByteBudget", budget)); }
        catch (Exception e) { throw new IllegalArgumentException("摘要输入无法序列化", e); }
        var response = caller.callSummary(List.of(LlmProvider.ChatMessage.text("system", com.pams.module.agent.prompt.PromptResolver.text(com.pams.module.agent.prompt.PromptResolver.Id.MEMORY_SUMMARY)), LlmProvider.ChatMessage.text("user", payload)), streamId);
        if(metrics!=null)metrics.usage(response.usage());
        if (response.degraded() || "length".equals(response.finishReason()) || response.content() == null
                || (response.toolCalls() != null && !response.toolCalls().isEmpty()))
            throw new IllegalStateException("摘要模型未返回完整结果");
        return response.content();
    }
}
