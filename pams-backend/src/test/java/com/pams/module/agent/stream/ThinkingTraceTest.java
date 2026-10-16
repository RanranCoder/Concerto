package com.pams.module.agent.stream;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思维链暂存的回归（V35）。
 *
 * <p>这里覆盖的都是"错了不会抛异常、只会让前端显示得很奇怪"的规则：
 * 同一轮片段是否拼在一起、失败的半截推理有没有被丢掉、空推理是否返回 null
 * （返回 "[]" 会让历史消息里出现一个空的思考段）。
 */
class ThinkingTraceTest {

    private final ThinkingTrace trace = new ThinkingTrace();

    @Test
    void accumulatesFragmentsIntoOneStepPerRound() {
        trace.begin("s1", 0);
        trace.append("s1", 0, "先确认");
        trace.append("s1", 0, "活动时间");
        trace.end("s1", 0);

        List<ThinkingTrace.Step> steps = trace.steps("s1");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).round()).isZero();
        assertThat(steps.get(0).text()).isEqualTo("先确认活动时间");
    }

    @Test
    void separateRoundsBecomeSeparateSteps() {
        trace.append("s1", 0, "第一轮推理");
        trace.end("s1", 0);
        trace.append("s1", 1, "第二轮推理");
        trace.end("s1", 1);

        assertThat(trace.steps("s1")).extracting(ThinkingTrace.Step::round).containsExactly(0, 1);
    }

    @Test
    void dropRemovesHalfWrittenStepFromFailedAttempt() {
        trace.append("s1", 0, "这段会因为失败作废");
        trace.drop("s1", 0);
        trace.append("s1", 1000, "重试后的完整推理");

        assertThat(trace.steps("s1")).extracting(ThinkingTrace.Step::round).containsExactly(1000);
        assertThat(trace.toJson("s1")).as("作废的那段不能留在落库内容里").doesNotContain("作废");
    }

    @Test
    void emptyTraceSerialisesToNullSoTheColumnStaysEmpty() {
        assertThat(trace.toJson("never-seen")).isNull();
        trace.append("s1", 0, "   ");
        assertThat(trace.toJson("s1")).as("只有空白字符不算推理").isNull();
    }

    @Test
    void jsonCarriesRoundAndDurationForHistoryReplay() throws Exception {
        trace.append("s1", 0, "想了一会儿");
        trace.end("s1", 0);

        String json = trace.toJson("s1");
        assertThat(json).isNotNull();
        assertThat(json).contains("\"round\":0").contains("想了一会儿").contains("durationMs");
        // 前端 AgentThinkingStep 期望的就是数组结构；这里用真实解析再确认一次
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).isArray()).isTrue();
    }

    @Test
    void clearReleasesTheStreamAfterPersisting() {
        trace.append("s1", 0, "已落库");
        trace.note("s1", "正在理解你的问题…");
        trace.clear("s1");
        assertThat(trace.steps("s1")).isEmpty();
        assertThat(trace.notesList("s1")).as("旁白也要随流释放，否则内存里留两份").isEmpty();
    }

    /* ------------------------------------------------ 编排旁白（V36） */

    @Test
    void keepsNarrationInOrderWithDurationUntilTheNextLine() throws Exception {
        trace.note("s1", "正在理解你的问题…");
        Thread.sleep(5);
        trace.note("s1", "已找到 2 条相关依据，正在整理…");

        List<Map<String, Object>> notes = trace.notesList("s1");
        assertThat(notes).hasSize(2);
        assertThat(notes.get(0)).containsEntry("note", "正在理解你的问题…");
        assertThat(notes.get(1)).containsEntry("note", "已找到 2 条相关依据，正在整理…");
        assertThat(notes.get(0).get("durationMs"))
                .as("第一条旁白持续到下一条才结束")
                .isNotNull();
        assertThat(notes.get(1))
                .as("最后一条没有下一行 → 不给 durationMs（给 0 会被渲染成耗时 0ms）")
                .doesNotContainKey("durationMs");
    }

    @Test
    void blankNarrationAndEmptyStreamDoNotProduceAColumn() {
        trace.note("s1", "   ");
        assertThat(trace.notesJson("s1")).isNull();
        assertThat(trace.notesJson("never-seen")).isNull();
    }

    @Test
    void narrationAndReasoningAreStoredSeparately() {
        trace.note("s1", "正在理解你的问题…");
        trace.append("s1", 0, "模型想了一会儿");

        assertThat(trace.notesJson("s1")).as("旁白不含推理原文").doesNotContain("模型想了一会儿");
        assertThat(trace.toJson("s1")).as("推理里不含旁白").doesNotContain("正在理解");
    }

    @Test
    void narrationIsBoundedPerStream() {
        for (int i = 0; i < 60; i++) trace.note("s1", "旁白 " + i);
        assertThat(trace.notesList("s1").size()).as("单流旁白行数有上限，不能无限攒").isLessThanOrEqualTo(40);
    }
}
