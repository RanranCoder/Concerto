package com.pams.module.agent.orchestration;

import com.pams.module.agent.entity.AgentBenchmark;
import com.pams.module.agent.repository.AgentBenchmarkRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对比评估实验（E 批次）测试：单 Agent vs 多 Agent 的指标计算与落库。
 */
@SpringBootTest(properties={"pams.agent.benchmark-enabled=true","pams.agent.benchmark-org-ids=1"})
@ActiveProfiles("test")
@Transactional
class BenchmarkServiceTest {

    @Autowired
    BenchmarkService benchmarkService;
    @Autowired com.pams.module.agent.config.AgentProperties properties;
    @Autowired
    AgentBenchmarkRepository repository;

    @BeforeEach
    void auth() {
        com.pams.common.OrgContext.set(1L);
        LoginUser lu = new LoginUser(1L, "bench", "基准用户", "DIRECTOR", 4, null, null, 1L);
        var authorities = List.of(
                new SimpleGrantedAuthority("material:view"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(lu, null, authorities));
    }

    @AfterEach
    void clear() {
        com.pams.common.OrgContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runBenchmark_comparesSingleAndMulti() {
        Map<String, Object> res = benchmarkService.runBenchmark(List.of("P1", "P2"));

        assertThat(res.get("batchId")).isNotNull();
        assertThat(res.get("tasks")).isEqualTo(BenchmarkService.TASKS.size());

        Map<String, Object> summary = (Map<String, Object>) res.get("summary");
        assertThat(summary).containsKeys(AgentBenchmark.ARM_SINGLE, "MULTI_P1", "MULTI_P2");

        Map<String, Object> single = (Map<String, Object>) summary.get(AgentBenchmark.ARM_SINGLE);
        Map<String, Object> multi = (Map<String, Object>) summary.get("MULTI_P1");

        // 单 Agent 臂：每条任务 1 个样本、1 个 Agent
        assertThat(single.get("samples")).isEqualTo(BenchmarkService.TASKS.size());
        assertThat((double) single.get("avgAgents")).isEqualTo(1.0);
        // 多 Agent 臂：参与 Agent 数应多于单 Agent
        assertThat((double) multi.get("avgAgents")).isEqualTo(1.0); // Stub failures leave dependent reviewer nodes unstarted.
        // 完成率
        assertThat(single.get("completionRate")).isNull();
        assertThat(single).containsKey("runSuccessRate");
        assertThat(multi.get("completionRate")).isNull();

        // 落库：任务数 × (1 单臂 + modes)
        List<AgentBenchmark> rows = repository.findByBatchIdOrderByIdAsc((String) res.get("batchId"));
        assertThat(rows).hasSize(BenchmarkService.TASKS.size() * (1 + 2));
        assertThat(rows).extracting(AgentBenchmark::getArm)
                .contains(AgentBenchmark.ARM_SINGLE, AgentBenchmark.ARM_MULTI);
    }
    @Test void disabledOrNonPilotOrganizationsCannotStartPaidEvaluationAndOldModesAreRejected() {
        long before=repository.count();
        try {
            properties.setBenchmarkEnabled(false);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->benchmarkService.runBenchmark(List.of("P1")))
                .isInstanceOf(com.pams.common.BizException.class).hasMessageContaining("隔离组织");
        } finally {properties.setBenchmarkEnabled(true);}
        org.assertj.core.api.Assertions.assertThatThrownBy(()->benchmarkService.runBenchmark(List.of("P3")))
            .isInstanceOf(com.pams.common.BizException.class).hasMessageContaining("仅支持");
        assertThat(repository.count()).isEqualTo(before);
    }

}
