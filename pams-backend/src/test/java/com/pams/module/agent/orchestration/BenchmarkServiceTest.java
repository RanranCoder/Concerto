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
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BenchmarkServiceTest {

    @Autowired
    BenchmarkService benchmarkService;
    @Autowired
    AgentBenchmarkRepository repository;

    @BeforeEach
    void auth() {
        LoginUser lu = new LoginUser(1L, "bench", "基准用户", "DIRECTOR", 4, null, null, 1L);
        var authorities = List.of(
                new SimpleGrantedAuthority("ROLE_DIRECTOR"),
                new SimpleGrantedAuthority("material:view"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(lu, null, authorities));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runBenchmark_comparesSingleAndMulti() {
        Map<String, Object> res = benchmarkService.runBenchmark(List.of("P1", "P3"));

        assertThat(res.get("batchId")).isNotNull();
        assertThat(res.get("tasks")).isEqualTo(BenchmarkService.TASKS.size());

        Map<String, Object> summary = (Map<String, Object>) res.get("summary");
        assertThat(summary).containsKeys(AgentBenchmark.ARM_SINGLE, AgentBenchmark.ARM_MULTI);

        Map<String, Object> single = (Map<String, Object>) summary.get(AgentBenchmark.ARM_SINGLE);
        Map<String, Object> multi = (Map<String, Object>) summary.get(AgentBenchmark.ARM_MULTI);

        // 单 Agent 臂：每条任务 1 个样本、1 个 Agent
        assertThat(single.get("samples")).isEqualTo(BenchmarkService.TASKS.size());
        assertThat((double) single.get("avgAgents")).isEqualTo(1.0);
        // 多 Agent 臂：参与 Agent 数应多于单 Agent
        assertThat((double) multi.get("avgAgents")).isGreaterThan(1.0);
        // 完成率
        assertThat((double) single.get("completionRate")).isEqualTo(1.0);
        assertThat((double) multi.get("completionRate")).isEqualTo(1.0);

        // 落库：任务数 × (1 单臂 + modes)
        List<AgentBenchmark> rows = repository.findByBatchIdOrderByIdAsc((String) res.get("batchId"));
        assertThat(rows).hasSize(BenchmarkService.TASKS.size() * (1 + 2));
        assertThat(rows).extracting(AgentBenchmark::getArm)
                .contains(AgentBenchmark.ARM_SINGLE, AgentBenchmark.ARM_MULTI);
    }
}
