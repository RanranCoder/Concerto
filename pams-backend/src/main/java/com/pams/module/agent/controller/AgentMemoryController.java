package com.pams.module.agent.controller;

import com.pams.common.Result;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.service.LongTermMemory;
import com.pams.security.LoginUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent/memories")
@PreAuthorize("isAuthenticated()")
public class AgentMemoryController {
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.service.AgentContextMetrics metrics;
    private final LongTermMemory memory;
    public AgentMemoryController(LongTermMemory memory){this.memory=memory;}
    private ToolContext ctx(LoginUser user){return new ToolContext(user,List.of(),null,null);}
    public record Save(String scope,String key,String content,boolean consent,Long revision,Integer days) {}
    public record Setting(boolean enabled) {}
    @GetMapping("/metrics") public Result<Map<String,Object>> metrics(@AuthenticationPrincipal LoginUser user){return Result.ok(metrics.snapshot(ctx(user)));}
    @GetMapping public Result<Map<String,Object>> list(@AuthenticationPrincipal LoginUser user) {
        var c=ctx(user);return Result.ok(Map.of("enabled",memory.enabled(c),"records",memory.list(c)));
    }
    @PostMapping public Result<LongTermMemory.Memory> save(@AuthenticationPrincipal LoginUser user,@RequestBody Save body) {
        return Result.ok(memory.save(ctx(user),body.scope()==null?"USER":body.scope(),body.key(),body.content(),body.consent(),body.revision(),null,body.days()==null?90:body.days()));
    }
    @PutMapping("/settings") public Result<Void> settings(@AuthenticationPrincipal LoginUser user,@RequestBody Setting body) {
        memory.setEnabled(ctx(user),body.enabled());return Result.ok();
    }
    @DeleteMapping("/{id}") public Result<Void> delete(@AuthenticationPrincipal LoginUser user,@PathVariable String id,@RequestParam long revision) {
        memory.delete(ctx(user),id,revision);return Result.ok();
    }
}
