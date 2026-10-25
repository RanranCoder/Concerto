package com.pams.module.agent.controller;

import com.pams.common.Result;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.service.*;
import com.pams.module.agent.orchestration.DagScheduler;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/agent/artifacts")
public class AgentArtifactController {
    private final ArtifactLedger ledger;
    private final ArtifactEditor editor;
    private final ConversationStore conversations;
    private final DagScheduler scheduler;
    private final AgentTaskRepository tasks;
    private final TaskGate gate;
    public AgentArtifactController(ArtifactLedger ledger,ArtifactEditor editor,ConversationStore conversations,DagScheduler scheduler,AgentTaskRepository tasks,TaskGate gate) {
        this.ledger=ledger;this.editor=editor;this.conversations=conversations;this.scheduler=scheduler;this.tasks=tasks;this.gate=gate;
    }
    @GetMapping
    public Result<List<Map<String,Object>>> list(@RequestParam Long conversationId,@AuthenticationPrincipal LoginUser user) {
        conversations.require(conversationId,user.getId());return Result.ok(ledger.listConversation(conversationId,ToolContext.current(conversationId,null)));
    }
    @GetMapping("/{id}")
    public Result<Map<String,Object>> detail(@PathVariable Long id,@RequestParam(required=false)Integer version) {
        return Result.ok(ledger.detail(id,version,ToolContext.current(null,null)));
    }
    @PostMapping("/{id}/versions")
    public Result<Map<String,Object>> edit(@PathVariable Long id,@RequestBody EditRequest request) {
        var ctx=ToolContext.current(null,null,request.mode());
        Long taskId=editor.edit(id,request.expectedVersion(),request.fields(),ctx);
        var out=new LinkedHashMap<String,Object>();out.put("detail",ledger.detail(id,null,ctx));out.put("task",gate.detail(taskId,ctx.getUserId()));
        var task=tasks.findById(taskId).orElseThrow();if(task.getRunId()!=null)out.put("run",scheduler.view(task.getRunId(),ctx));
        return Result.ok(out);
    }
    public record EditRequest(int expectedVersion,Map<String,String> fields,String mode) {}
}
