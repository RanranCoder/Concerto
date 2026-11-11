package com.pams.module.agent.controller;
import com.pams.common.Result;
import com.pams.module.agent.core.*;
import com.pams.module.agent.registry.AgentRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Effective scope only; excludes prompt text, credentials, token values and denied capabilities. */
@RestController @RequestMapping("/api/agent/diagnostics")
public class AgentDiagnosticsController {
    private final AgentRegistry agents;private final ToolRegistry tools;
    public AgentDiagnosticsController(AgentRegistry agents,ToolRegistry tools){this.agents=agents;this.tools=tools;}
    @GetMapping @PreAuthorize("hasAuthority('org:audit:view')")
    public Result<Map<String,Object>> effective(@RequestParam(defaultValue="standard")String mode){
        var ctx=ToolContext.current(null,null,mode);var rows=new ArrayList<Map<String,Object>>();
        for(var agent:agents.enabled()){
            var scope=agents.toolScopeOf(agent);var visible=tools.visibleTools(ctx).stream().filter(t->scope==null||scope.contains(t.name())).map(AgentTool::descriptor).toList();
            rows.add(Map.of("agent",agent.getName(),"effectiveTools",visible));
        }
        return Result.ok(Map.of("mode",ctx.getMode(),"roles",agents.promptConfigurations(),"experts",rows,"authorization","CURRENT_ACTOR_SCOPE_ONLY","productionReadiness","NOT_INFERRED_FROM_METADATA"));
    }
}
