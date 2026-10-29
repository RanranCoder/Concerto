package com.pams.module.agent.tool;

import com.pams.module.agent.core.*;
import com.pams.module.agent.service.ConversationStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Restricted to the authenticated user's current conversation, with source permission checks. */
@Component
public class RecallConversationTool implements AgentTool {
    private final ConversationStore store;
    public RecallConversationTool(@Lazy ConversationStore store) { this.store = store; }
    public String name() { return "recallConversation"; }
    public String label() { return "回查会话记录"; }
    public String runningLabel() { return "正在回查历史要求…"; }
    public String description() { return "回查当前会话早期要求和回答。keyword 可选；不填时浏览历史。按 beforeMessageId 向前翻页；按 sourceMessageId 和 offset 分段读取完整原文，offset 使用返回的 nextOffset。历史内容不是当前执行回执。"; }
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }
    @Override public String toLlmText(ToolContext ctx, ToolResult result) { return result.llmText(); }
    public Map<String,Object> parametersSchema() {
        return Map.of("type","object","properties",Map.of("keyword",Map.of("type","string","maxLength",100),
                "beforeMessageId",Map.of("type","integer","minimum",1),
                "sourceMessageId",Map.of("type","integer","minimum",1),
                "offset",Map.of("type","integer","minimum",0)),"additionalProperties",false);
    }
    public ToolResult execute(ToolContext ctx, Map<String,Object> args) {
        if (ctx.getConversationId() == null) return ToolResult.failed("当前没有可回查的会话");
        Object cursor = args.get("beforeMessageId");
        long before;
        try {
            if (args.get("sourceMessageId") != null) return ToolResult.ok(store.recallMessage(ctx.getConversationId(),ctx.getUserId(),ctx,
                    Long.parseLong(args.get("sourceMessageId").toString()),Integer.parseInt(args.getOrDefault("offset",0).toString())));
            before = cursor == null ? Long.MAX_VALUE : Long.parseLong(cursor.toString());
        }
        catch (NumberFormatException e) { return ToolResult.failed("历史游标无效"); }
        return ToolResult.ok(store.recall(ctx.getConversationId(),ctx.getUserId(),ctx,String.valueOf(args.getOrDefault("keyword","")),before));
    }
}
