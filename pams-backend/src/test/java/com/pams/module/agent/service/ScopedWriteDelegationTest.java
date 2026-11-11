package com.pams.module.agent.service;

import com.pams.module.agent.core.AgentActionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedWriteDelegationTest {
    private final AgentActionPlan draft = new AgentActionPlan(1L, "草稿", 1, List.of(), List.of(), List.of(), true, "test");

    @Test
    void personalArticleDelegationCannotBeInheritedFromAQuoteOrBroadenedToAnotherOwner() {
        var ownDraft = Map.<String, Object>of("activityId", 123L, "authorId", 7L, "articleType", "REPORT");
        var directive = "为活动 #123 保存一份本人报道草稿";
        assertThat(ScopedWriteDelegation.matches(ScopedWriteDelegation.ARTICLE, directive, 7L, draft, ownDraft)).isTrue();
        for (String message : List.of("请解释「" + directive + "」", "先预览" + directive,
                directive + "并提交审核", "不要" + directive, directive.replace("123", "321"))) {
            assertThat(ScopedWriteDelegation.matches(ScopedWriteDelegation.ARTICLE, message, 7L, draft, ownDraft)).isFalse();
        }
        assertThat(ScopedWriteDelegation.matches(ScopedWriteDelegation.ARTICLE, directive, 8L, draft, ownDraft)).isFalse();
        assertThat(ScopedWriteDelegation.matches("publishAnnouncement", directive, 7L, draft, ownDraft)).isFalse();
    }

    @Test
    void scheduleScopeBindsTheCompleteWindowDepartmentHeadcountAndPreparedFingerprint() {
        var directive = "为活动 #123 生成并保存 2026-11-16 至 2026-11-17 每天 09:00 至 10:00 部门 #77 每班 2 人的排班草稿";
        var prepared = new java.util.HashMap<String, Object>(Map.of(
                "activityId", 123L, "startDate", "2026-11-16", "endDate", "2026-11-17",
                "startTime", "09:00:00", "endTime", "10:00", "deptIds", List.of(77L),
                "peoplePerSlot", 2, "previewFingerprint", "a".repeat(64)));
        assertThat(ScopedWriteDelegation.matches(ScopedWriteDelegation.SCHEDULE, directive, 7L, draft, prepared)).isTrue();
        prepared.put("deptIds", List.of(77L, 78L));
        assertThat(ScopedWriteDelegation.matches(ScopedWriteDelegation.SCHEDULE, directive, 7L, draft, prepared)).isFalse();
        prepared.put("deptIds", List.of(77L));
        prepared.put("endTime", "10:01");
        assertThat(ScopedWriteDelegation.matches(ScopedWriteDelegation.SCHEDULE, directive, 7L, draft, prepared)).isFalse();
        prepared.put("endTime", "10:00");
        prepared.put("semester", "2026-2027-1");
        assertThat(ScopedWriteDelegation.matches(ScopedWriteDelegation.SCHEDULE, directive, 7L, draft, prepared)).isFalse();
    }
}
