package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多 Agent 协作（B/C 批次）测试：注册表 / 任务分解 / 工具作用域 / 专家委派 / Run-Step-黑板。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiAgentBaseTest {

    @Autowired
    AgentRegistry registry;
    @Autowired
    CoordinatorAgent coordinator;
    @Autowired
    AgentRunService runService;
    @Autowired
    SpecialistAgent specialist;
    @Autowired
    AgentEventPublisher eventPublisher;

    private static final ObjectMapper OM = new ObjectMapper();

    /** 构造一个带登录用户与流的工具上下文（授予 material:view 以便检索专家可用 searchMaterials）。 */
    private ToolContext ctx(long uid) {
        String streamId = eventPublisher.newStream("test", uid);
        LoginUser lu = new LoginUser(uid, "u" + uid, "测试用户", "DIRECTOR", 4, null, null, 1L);
        var authorities = List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("material:view"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DIRECTOR"));
        return new ToolContext(lu, authorities, null, streamId);
    }

    // ---------- B：注册表 ----------

    @Test
    void registry_seedsBuiltinAgents() {
        List<AgentDefinition> agents = registry.enabled();
        assertThat(agents).extracting(AgentDefinition::getName)
                .contains(AgentCatalog.COORDINATOR, AgentCatalog.RETRIEVAL, AgentCatalog.PLANNING,
                        AgentCatalog.WRITING, AgentCatalog.SCHEDULING, AgentCatalog.DATA,
                        AgentCatalog.REVIEWER);

        AgentDefinition retrieval = registry.byName(AgentCatalog.RETRIEVAL).orElseThrow();
        assertThat(registry.toolScopeOf(retrieval)).contains("queryActivityOverview", "searchMaterials");
        AgentDefinition coordinator = registry.byName(AgentCatalog.COORDINATOR).orElseThrow();
        assertThat(registry.toolScopeOf(coordinator)).isNullOrEmpty();
    }

    @Test
    void plan_routesByIntent() {
        assertThat(names(coordinator.plan("帮我给迎新晚会写一篇推文")))
                .contains(AgentCatalog.WRITING, AgentCatalog.REVIEWER);
        assertThat(names(coordinator.plan("查一下最近有哪些活动")))
                .contains(AgentCatalog.RETRIEVAL);
        assertThat(names(coordinator.plan("给新生批量加素拓分")))
                .contains(AgentCatalog.DATA, AgentCatalog.REVIEWER);
        assertThat(names(coordinator.plan("随便聊聊")))
                .contains(AgentCatalog.RETRIEVAL);
    }

    // ---------- C：工具作用域 + 委派 ----------

    @Test
    void scopedTools_respectAgentScope() {
        ToolContext c = ctx(1L);
        List<String> retrieval = specialist.scopedTools(AgentCatalog.RETRIEVAL, c)
                .stream().map(AgentTool::name).toList();
        assertThat(retrieval).contains("queryActivityOverview", "searchMaterials")
                .doesNotContain("createActivity", "draftArticleFromActivity");

        List<String> writing = specialist.scopedTools(AgentCatalog.WRITING, c)
                .stream().map(AgentTool::name).toList();
        assertThat(writing).contains("draftArticleFromActivity").doesNotContain("queryActivityOverview");

        assertThat(specialist.scopedTools(AgentCatalog.COORDINATOR, c)).isEmpty();
    }

    @Test
    void specialist_outOfScopeToolDenied() {
        ToolContext c = ctx(1L);
        // 把「创建活动」这个越出检索专家作用域的工具塞给它 → 必须被拒
        SpecialistAgent.Result res = specialist.execute(
                AgentCatalog.RETRIEVAL, "越权尝试", c,
                List.of(new LlmProvider.ToolCallRequest("t1", "createActivity", Map.of())),
                null, null);
        assertThat(res.invocations()).hasSize(1);
        assertThat(res.invocations().get(0).status()).isEqualTo("DENIED");
    }

    @Test
    void specialist_inScopeToolDispatched() {
        ToolContext c = ctx(1L);
        SpecialistAgent.Result res = specialist.execute(
                AgentCatalog.RETRIEVAL, "看看活动", c,
                List.of(new LlmProvider.ToolCallRequest("t1", "queryActivityOverview", Map.of())),
                null, null);
        assertThat(res.invocations()).hasSize(1);
        assertThat(res.invocations().get(0).name()).isEqualTo("queryActivityOverview");
        assertThat(res.invocations().get(0).status()).isNotEqualTo("DENIED");
    }

    // ---------- B/C：Run / Step / 黑板 / 归属 ----------

    @Test
    void run_persistsRunStepAndBlackboard() {
        ToolContext c = ctx(1L);
        AgentRun run = coordinator.run("帮我写一篇迎新晚会推文", c, null, "P1", c.getStreamId());
        assertThat(run.getId()).isNotNull();
        assertThat(run.getStatus()).isEqualTo(AgentRun.STATUS_SUCCESS);
        assertThat(run.getBlackboardJson()).isNotBlank();

        List<AgentStep> steps = runService.steps(run.getId());
        assertThat(steps).isNotEmpty();
        assertThat(steps.get(0).getAgentName()).isEqualTo(AgentCatalog.COORDINATOR);
        // 计划含 writing+reviewer，故应有委派步骤
        assertThat(steps).extracting(AgentStep::getAgentName)
                .contains(AgentCatalog.WRITING, AgentCatalog.REVIEWER);

        Blackboard bb = parse(run.getBlackboardJson());
        assertThat(bb).isNotNull();
        assertThat(bb.getPlan()).isNotEmpty();
        assertThat(bb.getFindings()).isNotEmpty();
        assertThat(bb.getDecisions()).isNotEmpty(); // 审查专家的 PASS/REVISE
    }

    @Test
    void run_isOwnershipIsolated() {
        ToolContext c = ctx(100L);
        AgentRun run = coordinator.run("查活动", c, null, "P1", c.getStreamId());
        assertThat(runService.getOwned(run.getId(), 100L).getId()).isEqualTo(run.getId());
        assertThatThrownBy(() -> runService.getOwned(run.getId(), 999L))
                .isInstanceOf(BizException.class);
    }

    private List<String> names(Blackboard bb) {
        return bb.getPlan().stream().map(Blackboard.PlanItem::getAgent).toList();
    }

    private Blackboard parse(String json) {
        try {
            return OM.readValue(json, Blackboard.class);
        } catch (Exception e) {
            return null;
        }
    }
}
