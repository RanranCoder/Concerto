package com.pams.module.agent.controller;

import com.pams.common.Result;
import com.pams.module.agent.entity.AgentBenchmark;
import com.pams.module.agent.orchestration.BenchmarkService;
import com.pams.module.agent.repository.AgentBenchmarkRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多 Agent 对比评估实验接口（E 批次）。ADMIN 专用（会实际执行多轮协作）。
 */
@RestController
@RequestMapping("/api/agent/bench")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR')")
public class AgentBenchController {

    private final BenchmarkService benchmarkService;
    private final AgentBenchmarkRepository repository;

    public AgentBenchController(BenchmarkService benchmarkService, AgentBenchmarkRepository repository) {
        this.benchmarkService = benchmarkService;
        this.repository = repository;
    }

    /** 运行一次批量对比实验（单 Agent vs 多 Agent）。 */
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody(required = false) BenchRequest req) {
        List<String> modes = req == null ? null : req.modes();
        return Result.ok(benchmarkService.runBenchmark(modes));
    }

    /** 内置任务集（前端展示用）。 */
    @GetMapping("/tasks")
    public Result<List<Map<String, String>>> tasks() {
        return Result.ok(BenchmarkService.TASKS.stream()
                .map(t -> Map.of("code", t.code(), "text", t.text()))
                .toList());
    }

    /** 最近实验结果。 */
    @GetMapping("/results")
    public Result<List<AgentBenchmark>> results() {
        return Result.ok(repository.findTop200ByOrderByIdDesc());
    }

    public record BenchRequest(List<String> modes) {
    }
}
