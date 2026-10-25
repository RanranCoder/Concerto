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
                        "你是学生组织工作台的协调者。负责理解用户目标、拆解为子任务、"
                                + "选择合适的专家 Agent 执行、汇总结果并裁决冲突。你不直接调用业务工具。",
                        List.of()),
                new Def(RETRIEVAL, "检索专家",
                        "查询业务数据与知识库",
                        "你是检索专家。只依据检索到的证据作答，给出结论 + 依据 + 引用，绝不编造。",
                        List.of("queryMyPendingWork", "queryActivityOverview", "queryActivityTasks",
                                "searchMaterials", "searchArticles", "queryNotifications",
                                "queryCommonFreeTime", "queryAttendance", "queryCreditRecords",
                                "queryRosterSigninStats", "queryPartyMemberProgress")),
                new Def(PLANNING, "策划专家",
                        "活动策划与策划书",
                        "你是策划专家。产出活动方案与策划书章节草稿，只落 DRAFT，不提交审核。",
                        List.of("createActivity", "draftPlanSections")),
                new Def(WRITING, "文书专家",
                        "推文 / 新闻稿 / 公告",
                        "你是文书专家。基于活动素材撰写推文、新闻稿、公告草稿；正文须过服务端净化。",
                        List.of("draftArticleFromActivity", "publishAnnouncement")),
                new Def(SCHEDULING, "排班专家",
                        "排班与任务分解",
                        "你是排班专家。生成活动排班与筹备任务，复用现有规则服务，输出冲突检测结论。",
                        List.of("generateActivitySchedule", "generateActivityTasks", "createSeatLayout")),
                new Def(DATA, "事务专家",
                        "批量事务（须确认门禁）",
                        "你是事务专家。批量加分/建成员/发通知必须先出拟执行清单（含 skipped 明细），"
                                + "待用户确认后执行。",
                        List.of("previewCreditBatch", "createMember", "sendNotification")),
                new Def(REVIEWER, "审查专家",
                        "质量 / 事实 / 合规审查",
                        "你是审查专家。只读黑板与产物，检查完整性、事实一致性、合规与越权风险，"
                                + "输出 PASS 或 REVISE（含理由）。你没有任何业务工具。",
                        List.of()),
                new Def(GENERALIST, "通才助手",
                        "单 Agent 基线（对比实验）：一个 Agent 使用全部可见工具",
                        "你是学生组织工作台的通才助手，可使用全部可见工具独立完成用户目标。",
                        null));
    }
}
