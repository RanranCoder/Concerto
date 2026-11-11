package com.pams.module.agent.registry;

import java.util.List;

/**
 * Agent 名称常量 + 内置默认定义（工具作用域 / 角色提示词）。
 *
 * <p>默认定义由 {@link AgentDefinitionSeeder} 幂等写入 {@code agent_definition}，
 * 之后可改库；{@link AgentRegistry} 优先读库，库为空时回退到本目录。
 * 「工具作用域」在本批次（B）只作声明与展示，实际按作用域过滤工具在 C 批次接入。
 */
public final class AgentCatalog {

    public static final String COORDINATOR = "coordinator";
    public static final String RETRIEVAL = "retrieval";
    public static final String PLANNING = "planning";
    public static final String WRITING = "writing";
    public static final String SCHEDULING = "scheduling";
    public static final String DATA = "data";
    public static final String REVIEWER = "reviewer";
    /** 单 Agent 基线（对比实验专用）：一个 Agent 使用全部可见工具。 */
    public static final String GENERALIST = "generalist";

    private AgentCatalog() {
    }

    /**
     * Agent 名称 → 中文展示名（前端过程区/团队面板显示用）。
     *
     * <p>为什么需要它：协作事件原本只带 {@code agent: "retrieval"} 这样的英文 id，
     * 前端只能把 id 原样贴到界面上，用户看到的是「retrieval」而不是「检索专家」。
     * 这里以 {@link #defaults()} 为准做映射（库内改名仍可用 {@code /agent/team/agents} 覆盖展示）。
     */
    public static String displayName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        for (Def d : defaults()) {
            if (d.name().equals(name)) {
                return d.displayName();
            }
        }
        return name;
    }

    /** 一条默认定义。 */
    public record Def(String name, String displayName, String description,
                      String rolePrompt, List<String> toolScope) {
    }

    public static List<Def> defaults() {
        return List.of(
                new Def(COORDINATOR, "协调者",
                        "意图理解 · 任务分解 · 选择专家 · 汇总裁决",
                        prompt(com.pams.module.agent.prompt.PromptResolver.Id.COORDINATOR),
                        List.of()),
                new Def(RETRIEVAL, "检索专家",
                        "查询业务数据与知识库",
                        prompt(com.pams.module.agent.prompt.PromptResolver.Id.RETRIEVAL),
                        List.of("findMembers", "listOrgUnits", "getActivityDetail", "listPlanVersions", "readPlanDocument", "queryMyPendingWork", "queryActivityOverview", "queryActivityTasks",
                                "searchMaterials", "readMaterialDocument", "searchArticles", "readArticleDocument", "getArticleReviewStatus", "getPlanReviewStatus", "queryActivityAgenda", "queryActivityScores", "queryActivitySchedules", "querySeatLayout", "queryAnnouncements", "searchKnowledgeEvidence", "readKnowledgeEvidence", "queryNotifications",
                                "queryCommonFreeTime", "queryAttendance", "queryCreditRecords",
                                "queryRosterSigninStats", "queryPartyMemberProgress", "recallConversation")),
                new Def(PLANNING, "策划专家",
                        "活动策划与策划书",
                        prompt(com.pams.module.agent.prompt.PromptResolver.Id.PLANNING),
                        List.of("createActivity", "draftPlanSections", "recallConversation", "getActivityDetail", "listPlanVersions", "readPlanDocument", "preparePlanRevision", "getPlanReviewStatus", "preparePlanSubmission", "searchKnowledgeEvidence", "readKnowledgeEvidence")),
                new Def(WRITING, "文书专家",
                        "推文 / 新闻稿 / 公告",
                        prompt(com.pams.module.agent.prompt.PromptResolver.Id.WRITING),
                        List.of("draftArticleFromActivity", "publishAnnouncement", "recallConversation", "searchArticles", "readArticleDocument", "prepareArticleRevision", "prepareArticleSubmission", "getArticleReviewStatus", "searchKnowledgeEvidence", "readKnowledgeEvidence")),
                new Def(SCHEDULING, "排班专家",
                        "排班与任务分解",
                        prompt(com.pams.module.agent.prompt.PromptResolver.Id.SCHEDULING),
                        List.of("generateActivitySchedule", "generateActivityTasks", "createSeatLayout", "recallConversation", "queryActivityTasks", "prepareTaskProgressUpdate", "prepareTaskPlanChange", "findMembers", "listOrgUnits", "queryActivityOverview", "getActivityDetail", "queryActivitySchedules", "prepareSchedulePublication", "querySeatLayout", "queryActivityAgenda")),
                new Def(DATA, "事务专家",
                        "批量事务（须确认门禁）",
                        prompt(com.pams.module.agent.prompt.PromptResolver.Id.DATA),
                        List.of("previewCreditBatch", "createMember", "sendNotification", "recallConversation", "findMembers", "listOrgUnits", "resolveNotificationAudience", "getNotificationDeliveryStatus", "queryActivityTasks", "getActivityDetail")),
                new Def(REVIEWER, "审查专家",
                        "质量 / 事实 / 合规审查",
                        prompt(com.pams.module.agent.prompt.PromptResolver.Id.SPECIALIST_REVIEWER),
                        List.of()),
                new Def(GENERALIST, "通才助手",
                        "单 Agent 基线（对比实验）：一个 Agent 使用全部可见工具",
                        prompt(com.pams.module.agent.prompt.PromptResolver.Id.GENERALIST),
                        null));
    }
    private static String prompt(com.pams.module.agent.prompt.PromptResolver.Id id){return com.pams.module.agent.prompt.PromptResolver.text(id);}
}
