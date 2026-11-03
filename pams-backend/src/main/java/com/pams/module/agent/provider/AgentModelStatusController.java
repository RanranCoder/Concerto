package com.pams.module.agent.provider;

import com.pams.common.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/model-status")
@PreAuthorize("isAuthenticated()")
public class AgentModelStatusController {
    private final AgentProviderService providers;
    public AgentModelStatusController(AgentProviderService providers) { this.providers = providers; }
    @GetMapping public Result<AgentProviderService.ModelStatus> status() { return Result.ok(providers.modelStatus()); }
}
