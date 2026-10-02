package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentBenchmark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentBenchmarkRepository extends JpaRepository<AgentBenchmark, Long> {

    List<AgentBenchmark> findByBatchIdOrderByIdAsc(String batchId);

    List<AgentBenchmark> findTop200ByOrderByIdDesc();
}
