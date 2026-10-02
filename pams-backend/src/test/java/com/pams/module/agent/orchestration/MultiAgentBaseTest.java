package com.pams.module.agent.orchestration;

import com.pams.common.BizException;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.registry.AgentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多 Agent 协作基座（B 批次）测试：注册表 / 任务分解 / Run-Step 落库 / 黑板。
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

    @Test
    void registry_seedsBuiltinAgents() {
        List<AgentDefinition> agents = registry.enabled();
        assertThat(agents).extracting(AgentDefinition::getName)
                .contains(AgentCatalog.COORDINATOR, AgentCatalog.RETRIEVAL, AgentCatalog.PLANNING,
                        AgentCatalog.WRITING, AgentCatalog.SCHEDULING, AgentCatalog.DATA,
                        AgentCatalog.REVIEWER);

        AgentDefinition retrieval = registry.byName(AgentCatalog.RETRIEVAL).orElseThrow();
        List<String> scope = registry.toolScopeOf(retrieval);
        assertThat(scope).contains("queryActivityOverview", "searchMaterials");

        // 协调者与审查者声明的作用域为空（协调者只调度、审查者只读黑板）
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

    @Test
    void run_persistsRunStepAndBlackboard() {
        AgentRun run = coordinator.run("帮我写一篇迎新晚会推文", 1L, null, "P1", null, null);
        assertThat(run.getId()).isNotNull();
        assertThat(run.getStatus()).isEqualTo(AgentRun.STATUS_SUCCESS);
        assertThat(run.getBlackboardJson()).isNotBlank();

        List<AgentStep> steps = runService.steps(run.getId());
        assertThat(steps).isNotEmpty();
        assertThat(steps.get(0).getAgentName()).isEqualTo(AgentCatalog.COORDINATOR);
        assertThat(steps.get(0).getStatus()).isEqualTo(AgentStep.STATUS_SUCCESS);

        // 黑板可反序列化且计划非空
        Blackboard bb = parse(run.getBlackboardJson());
        assertThat(bb).isNotNull();
        assertThat(bb.getPlan()).isNotEmpty();
        assertThat(bb.getFindings()).isNotEmpty();
    }

    @Test
    void run_isOwnershipIsolated() {
        AgentRun run = coordinator.run("查活动", 100L, null, "P1", null, null);
        assertThat(runService.getOwned(run.getId(), 100L).getId()).isEqualTo(run.getId());
        assertThatThrownBy(() -> runService.getOwned(run.getId(), 999L))
                .isInstanceOf(BizException.class);
    }

    private List<String> names(Blackboard bb) {
        return bb.getPlan().stream().map(Blackboard.PlanItem::getAgent).toList();
    }

    private Blackboard parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Blackboard.class);
        } catch (Exception e) {
            return null;
        }
    }
}
