package com.pams.module.agent.provider;

import com.pams.common.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/providers")
@PreAuthorize("hasAuthority('org:manage')")
public class AgentProviderController {
    private final AgentProviderService service;
    public AgentProviderController(AgentProviderService service) { this.service = service; }
    public record Activation(long version, long defaultVersion) {}
    @GetMapping public Result<AgentProviderService.Listing> list() { return Result.ok(service.list()); }
    @PostMapping public Result<AgentProviderService.View> create(@RequestBody AgentProviderService.Draft d) {
        if (d.id() != null) throw new com.pams.common.BizException(4400, "新增不能指定 ID");
        return Result.ok(service.save(d));
    }
    @PutMapping("/{id}") public Result<AgentProviderService.View> update(@PathVariable long id, @RequestBody AgentProviderService.Draft d) {
        if (!java.util.Objects.equals(d.id(), id)) throw new com.pams.common.BizException(4400, "配置 ID 不一致");
        return Result.ok(service.save(d));
    }
    @PostMapping("/test") public Result<AgentProviderService.TestResult> test(@RequestBody AgentProviderService.Draft d) { return Result.ok(service.test(d)); }
    @PostMapping("/{id}/activate") public Result<Void> activate(@PathVariable long id, @RequestBody Activation d) {
        service.activate(id, d.version, d.defaultVersion); return Result.ok();
    }
    @DeleteMapping("/default") public Result<Void> reset(@RequestParam long defaultVersion) { service.activate(null, 0, defaultVersion); return Result.ok(); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable long id, @RequestParam long version) { service.delete(id, version); return Result.ok(); }
}
