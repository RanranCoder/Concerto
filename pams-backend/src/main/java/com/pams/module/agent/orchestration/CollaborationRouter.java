package com.pams.module.agent.orchestration;

import com.pams.module.agent.registry.AgentCatalog;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 协作路由器（C 批次）—— 决定「这一轮要不要派专家团队」。
 *
 * <p><b>为什么判定独立成一层，而不是做成一个工具让 LLM 自己调</b>：
 * 判定是本研究要对论文量化的对象（批次 E：单 Agent vs 多 Agent 的完成率/轮次/耗时/事实一致性）。
 * 做成工具的话判定逻辑埋进提示词里，无法单独度量、也无法单独调参。
 *
 * <p><b>判定输入是「确定性计划」而不是原始文本</b>：
 * 复用 {@link CoordinatorAgent#plan(String)} 的规则分解结果 —— 它已经把任务落到具体专家上，
 * 于是判定退化成「计划里有几个专家、有没有产出型环节」，比在关键词上叠启发式可靠得多。
 *
 * <p><b>保守优先</b>：任何不确定都回落到单 Agent。协作是增强路径而非必需路径，
 * 误升级的代价（延迟、成本）比不升级更明显。
 */
@Component
public class CollaborationRouter {

    /** 一次协作最多派几个专家（超出则退回单 Agent，避免复杂度与成本失控）。 */
    public static final int MAX_EXPERTS = 5;

    /**
     * 判定结果。
     *
     * @param upgrade   是否升级为协作
     * @param reason    面向用户的判定理由（前端会显示成「判定为协作：…」）
     * @param mode      协作模式 P1 / P2 / P3 / P4
     * @param experts   计划中的专家（仅 upgrade=true 时有意义）
     */
    public record Decision(boolean upgrade, String reason, String mode, List<String> experts) {

        public static Decision single(String reason) {
            return new Decision(false, reason, null, List.of());
        }
    }

    private final CoordinatorAgent coordinator;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.pams.module.agent.config.AgentProperties properties;

    public CollaborationRouter(CoordinatorAgent coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * 判定是否升级为协作。
     *
     * <p>升级条件（需同时满足「有产出环节」+「至少一个触发信号」）：
     * <ol>
     *   <li><b>复合动作</b>：一句话里含多个动作连接词（并/然后/同时/顺便）—— 单轮工具循环难以一次收口；</li>
     *   <li><b>跨域复合</b>：计划里同时出现检索型与产出型专家（如「查资料 + 写推文」）；</li>
     *   <li><b>正式产出</b>：目标是要成文的正式材料（策划书/推文/新闻稿/公告），且需要审查把关。</li>
     * </ol>
     */
    public Decision route(String message, String mode, boolean collabEnabled) {
        String m = mode == null ? "standard" : mode.trim().toLowerCase();
        if (!"standard".equals(m)) {
            // 只读档位不写数据、对话档位走 KbPipeline 问答流水线，两者都不涉及「派专家」
            return Decision.single("当前档位不走协作");
        }
        if (properties!=null && (!properties.isCollaborationEnabled() || (!properties.getCollaborationOrgIds().isEmpty() && !properties.getCollaborationOrgIds().contains(com.pams.common.OrgContext.get())))) {
            return Decision.single("当前组织使用单 Agent 稳定路径");
        }
        if (!collabEnabled) {
            return Decision.single("自动协作已关闭");
        }
        String goal = message == null ? "" : message.trim();
        if (goal.isEmpty()) {
            return Decision.single("空输入");
        }

        Blackboard plan;
        try {
            plan = coordinator.plan(goal);
        } catch (Exception e) {
            // 判定本身失败绝不能影响对话可用性
            return Decision.single("任务分解失败，按单 Agent 处理");
        }

        List<String> experts = plan.getPlan().stream()
                .map(Blackboard.PlanItem::getAgent)
                .distinct()
                .toList();

        if (experts.size() > MAX_EXPERTS) {
            return Decision.single("任务规模超出协作预算");
        }

        // 是否含「产出型」环节（写作/策划/事务），以及是否需要审查
        boolean hasProducer = experts.stream().anyMatch(this::isProducer);
        boolean hasRetrieval = experts.contains(AgentCatalog.RETRIEVAL);
        boolean compoundVerb = containsAny(goal, "并", "然后", "同时", "顺便", "以及", "接着");
        boolean formalDoc = containsAny(goal, "策划书", "推文", "新闻稿", "公告", "报道", "稿件", "方案");

        // 纯查询：只有检索专家 → 单 Agent 一次工具调用就够
        if (!hasProducer) {
            return Decision.single("单点查询，无需组队");
        }
        // 有产出且（跨域 或 复合动作 或 正式文稿）→ 升级
        if (!(hasRetrieval && hasProducer) && !compoundVerb && !formalDoc) {
            return Decision.single("单一产出任务，单 Agent 即可");
        }
        // 需人工确认的对外内容（公告/推文）走反思回路，由审查专家把关后再出确认卡
        boolean needsReview = formalDoc && containsAny(goal, "公告", "推文", "新闻", "报道", "发布");
        String collabMode = needsReview ? "P3" : "P1";

        String reason = buildReason(experts, hasRetrieval, compoundVerb, formalDoc);
        return new Decision(true, reason, collabMode, experts);
    }

    private String buildReason(List<String> experts, boolean crossDomain, boolean compoundVerb, boolean formalDoc) {
        StringBuilder sb = new StringBuilder();
        if (crossDomain) {
            sb.append("需要跨域检索与成文");
        } else if (formalDoc) {
            sb.append("需要产出正式材料");
        } else if (compoundVerb) {
            sb.append("含多个连续动作");
        } else {
            sb.append("任务需要多步协作");
        }
        sb.append("，已派 ").append(experts.size()).append(" 个专家（");
        sb.append(String.join("、", experts.stream().map(AgentCatalog::displayName).toList()));
        sb.append("）");
        return sb.toString();
    }

    private boolean isProducer(String agent) {
        return AgentCatalog.WRITING.equals(agent)
                || AgentCatalog.PLANNING.equals(agent)
                || AgentCatalog.DATA.equals(agent)
                || AgentCatalog.SCHEDULING.equals(agent);
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
