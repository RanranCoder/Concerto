package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContextBudgetTest {
    private AgentProperties properties(int window) {
        var p = new AgentProperties(); p.setContextWindowTokens(window); p.setMaxTokens(300); p.setContextSafetyTokens(100);
        return p;
    }
    @Test void removesWholeHistoricalTurnsAndKeepsCurrentCorrectionAndToolProtocol() {
        var p = properties(2400); p.setContextToolResultTokens(256);
        var call = new LlmProvider.ToolCallRequest("call-1", "query", Map.of());
        var reasoning = "不能删除的推理";
        var input = List.of(LlmProvider.ChatMessage.text("system","规则"),
                LlmProvider.ChatMessage.text("user","旧要求".repeat(500)), LlmProvider.ChatMessage.text("assistant","旧回答"),
                LlmProvider.ChatMessage.text("system",ContextBudget.TURN_BOUNDARY),
                LlmProvider.ChatMessage.text("user","改为周六，预算两千"),
                LlmProvider.ChatMessage.assistantCalling("",List.of(call),reasoning),
                LlmProvider.ChatMessage.toolResult("call-1","业务数据".repeat(1000)),
                LlmProvider.ChatMessage.text("user","请继续生成预览"));
        var actual = new ContextBudget(p).fit(input,List.of());
        assertThat(actual).noneMatch(m -> "旧回答".equals(m.content()));
        assertThat(actual).anyMatch(m -> "改为周六，预算两千".equals(m.content()));
        assertThat(actual).anyMatch(m -> reasoning.equals(m.reasoningContent()) && m.toolCalls().equals(List.of(call)));
        assertThat(actual).anyMatch(m -> "call-1".equals(m.toolCallId()) && m.content().contains("节选"));
        assertThat(actual.stream().mapToInt(ContextBudget::cost).sum()).isLessThan(2000);
        assertThat(input.get(6).content()).hasSize(4000);
    }
    @Test void refusesOversizedLiveInstructionRatherThanSilentlyTruncatingIt() {
        var budget = new ContextBudget(properties(800));
        assertThatThrownBy(() -> budget.fit(List.of(LlmProvider.ChatMessage.text("user","约束".repeat(1000))),List.of()))
                .isInstanceOf(BizException.class);
    }
    @Test void reducesOlderToolResultsWhenTheirCombinedSizeOverflows() {
        var p = properties(1500);
        p.setContextToolResultTokens(600);
        var task = LlmProvider.ChatMessage.text("user", "查询并比较两个结果");
        var call = LlmProvider.ChatMessage.assistantCalling("", List.of(
                new LlmProvider.ToolCallRequest("first", "query", Map.of()),
                new LlmProvider.ToolCallRequest("second", "query", Map.of())), "查询两个来源");
        var older = LlmProvider.ChatMessage.toolResult("first", "甲".repeat(200));
        var newer = LlmProvider.ChatMessage.toolResult("second", "乙".repeat(150));
        var result = new ContextBudget(p).fit(List.of(task, call, older, newer), List.of());

        assertThat(result).hasSize(4).startsWith(task, call).endsWith(newer);
        assertThat(result.get(2).toolCallId()).isEqualTo("first");
        assertThat(result.get(2).content()).isNotEqualTo(older.content());
        assertThat(result.stream().mapToInt(ContextBudget::cost).sum()).isLessThanOrEqualTo(1098);
    }
    @Test void toolSchemasAndReasoningConsumeBudget() {
        var budget = new ContextBudget(properties(900));
        var messages = List.of(LlmProvider.ChatMessage.text("user","本轮任务"));
        assertThatThrownBy(() -> budget.fit(messages,List.of(new LlmProvider.ToolSpec("query","说明".repeat(200),Map.of()))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> budget.fit(List.of(messages.getFirst(),
                LlmProvider.ChatMessage.assistantCalling("",List.of(new LlmProvider.ToolCallRequest("c","q",Map.of())),"推理".repeat(200))),List.of()))
                .isInstanceOf(BizException.class);
    }
    @Test void excerptPreservesUnicodeCodePoints() {
        String excerpt = ContextBudget.excerpt("😀".repeat(100),100);
        assertThat(excerpt).doesNotContain("�").contains("节选");
        assertThat(ContextBudget.tokens(excerpt)).isLessThanOrEqualTo(100);
    }
    @Test void protectedSummarySurvivesTrimmingOfOldTurns() {
        var summary=LlmProvider.ChatMessage.text("user",ContextBudget.MEMORY_PREFIX+"原始目标：安排活动，预算两千");
        var result=new ContextBudget(properties(1600)).fit(List.of(
                LlmProvider.ChatMessage.text("system","规则"),summary,
                LlmProvider.ChatMessage.text("user","无关历史".repeat(500)),LlmProvider.ChatMessage.text("assistant","旧回答"),
                LlmProvider.ChatMessage.text("system",ContextBudget.TURN_BOUNDARY),LlmProvider.ChatMessage.text("user","继续")),List.of());
        assertThat(result).contains(summary).noneMatch(m->"旧回答".equals(m.content()));
    }
}
