package com.pams.module.agent.orchestration;

import com.pams.module.agent.registry.AgentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 协作路由判定回归（C 批次）。
 *
 * <p>这个判定是本功能的**唯一不确定点**：判错了要么让简单问答白等一轮协作（慢、贵），
 * 要么该组队时没组队（能力没用上）。所以每条分支都要有断言固定住，
 * 后续调参（这是论文批次 E 的对象）时才有回归网。
 *
 * <p>用 Mockito 替身而不是 {@code @SpringBootTest}：判定的输入是
 * {@link CoordinatorAgent#plan(String)} 的确定性输出，把它替换掉就能纯测判定逻辑本身，
 * 不依赖数据库与 LLM。
 */
class CollaborationRouterTest {

    private CoordinatorAgent coordinator;
    private CollaborationRouter router;

    @BeforeEach
    void setUp() {
        coordinator = Mockito.mock(CoordinatorAgent.class);
        router = new CollaborationRouter(coordinator);
    }

    private void planReturns(String... agents) {
        Blackboard bb = new Blackboard();
        int id = 1;
        for (String a : agents) {
            Blackboard.PlanItem item = new Blackboard.PlanItem();
            item.setId(id++);
            item.setAgent(a);
            item.setTask("task-" + a);
            bb.getPlan().add(item);
        }
        when(coordinator.plan(anyString())).thenReturn(bb);
    }

    @Test
    void 单点查询不升级为协作() {
        // 「有多少个活动」—— 规则分解只出检索专家，一次工具调用就够
        planReturns(AgentCatalog.RETRIEVAL);
        CollaborationRouter.Decision d = router.route("现在系统里有多少个活动？", "standard", true);
        assertThat(d.upgrade()).isFalse();
        assertThat(d.reason()).contains("单点查询");
    }

    @Test
    void 单一产出任务不升级为协作() {
        // 有产出环节，但没有跨域、没有复合动作、也不是正式文稿 → 单 Agent 即可
        planReturns(AgentCatalog.DATA);
        CollaborationRouter.Decision d = router.route("给张三加两分素拓", "standard", true);
        assertThat(d.upgrade()).isFalse();
    }

    @Test
    void 跨域检索加成文升级为协作_串行() {
        planReturns(AgentCatalog.RETRIEVAL, AgentCatalog.WRITING);
        CollaborationRouter.Decision d = router.route("查一下上届迎新晚会的材料", "standard", true);
        assertThat(d.upgrade()).isTrue();
        assertThat(d.mode()).isEqualTo("P1");
        assertThat(d.experts()).containsExactly(AgentCatalog.RETRIEVAL, AgentCatalog.WRITING);
    }

    @Test
    void 对外发布类正式文稿走反思回路() {
        // 推文/公告要经审查把关后再出确认卡 → P3
        planReturns(AgentCatalog.WRITING, AgentCatalog.REVIEWER);
        CollaborationRouter.Decision d = router.route("把这篇稿子作为公告发布出去", "standard", true);
        assertThat(d.upgrade()).isTrue();
        assertThat(d.mode()).isEqualTo("P3");
    }

    @Test
    void 正式文稿即使单专家也升级为协作() {
        // 有审查环节的正式材料值得反思回路，哪怕只有一位产出专家
        planReturns(AgentCatalog.WRITING, AgentCatalog.REVIEWER);
        CollaborationRouter.Decision d = router.route("写一份主题党日活动的完整策划书", "standard", true);
        assertThat(d.upgrade()).isTrue();
    }

    @Test
    void 复合动作升级为协作() {
        planReturns(AgentCatalog.SCHEDULING, AgentCatalog.WRITING);
        CollaborationRouter.Decision d = router.route("先排好班，然后写一篇推文", "standard", true);
        assertThat(d.upgrade()).isTrue();
    }

    @Test
    void 关闭开关后一律不升级() {
        planReturns(AgentCatalog.RETRIEVAL, AgentCatalog.WRITING);
        CollaborationRouter.Decision d = router.route("查材料并写推文", "standard", false);
        assertThat(d.upgrade()).isFalse();
        assertThat(d.reason()).contains("自动协作已关闭");
    }

    @Test
    void 只读与对话档位都不升级() {
        planReturns(AgentCatalog.RETRIEVAL, AgentCatalog.WRITING);
        assertThat(router.route("查材料并写推文", "readonly", true).upgrade()).isFalse();
        assertThat(router.route("入党流程是什么", "kb", true).upgrade()).isFalse();
    }

    @Test
    void 空输入不升级() {
        assertThat(router.route("   ", "standard", true).upgrade()).isFalse();
    }

    @Test
    void 分解失败时回落到单Agent() {
        // 判定本身异常绝不能让对话不可用 —— 这是"保守优先"原则的兜底
        when(coordinator.plan(anyString())).thenThrow(new RuntimeException("boom"));
        CollaborationRouter.Decision d = router.route("查材料并写推文", "standard", true);
        assertThat(d.upgrade()).isFalse();
        assertThat(d.reason()).contains("按单 Agent 处理");
    }

    @Test
    void 超出专家预算时回落单Agent() {
        planReturns(AgentCatalog.RETRIEVAL, AgentCatalog.PLANNING, AgentCatalog.WRITING,
                AgentCatalog.SCHEDULING, AgentCatalog.DATA, AgentCatalog.REVIEWER);
        CollaborationRouter.Decision d = router.route("把这些都办了", "standard", true);
        assertThat(d.upgrade()).isFalse();
        assertThat(d.reason()).contains("协作预算");
    }

    @Test
    void 判定理由包含中文专家名() {
        // 前端直接把 reason 显示给用户，出现英文 id 就等于把内部标识暴露了
        planReturns(AgentCatalog.RETRIEVAL, AgentCatalog.WRITING);
        CollaborationRouter.Decision d = router.route("查材料并写推文", "standard", true);
        assertThat(d.reason()).contains("检索专家").contains("文书专家");
        assertThat(d.reason()).doesNotContain(AgentCatalog.RETRIEVAL);
    }

    @Test
    void 专家顺序去重() {
        planReturns(AgentCatalog.RETRIEVAL, AgentCatalog.RETRIEVAL, AgentCatalog.WRITING);
        CollaborationRouter.Decision d = router.route("查材料并写推文", "standard", true);
        assertThat(d.experts()).containsExactly(AgentCatalog.RETRIEVAL, AgentCatalog.WRITING);
    }
    @Test void organizationRolloutAndRollbackChangeOnlyNewRouting() {
        planReturns(AgentCatalog.RETRIEVAL,AgentCatalog.WRITING,AgentCatalog.REVIEWER);
        var props=new com.pams.module.agent.config.AgentProperties();props.setCollaborationOrgIds(java.util.Set.of(10L));
        org.springframework.test.util.ReflectionTestUtils.setField(router,"properties",props);
        try {
            com.pams.common.OrgContext.set(9L);assertThat(router.route("查资料并写推文","standard",true).upgrade()).isFalse();
            com.pams.common.OrgContext.set(10L);assertThat(router.route("查资料并写推文","standard",true).upgrade()).isTrue();
            props.setCollaborationEnabled(false);assertThat(router.route("查资料并写推文","standard",true).upgrade()).isFalse();
            org.mockito.Mockito.verify(coordinator,org.mockito.Mockito.times(1)).plan(anyString());
        } finally {com.pams.common.OrgContext.clear();}
    }

}

// 需要 import List 之外的类型提示：Decision 是 record，用访问器断言
// （显式列出以免读者以为是字段访问）
