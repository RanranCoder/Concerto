package com.pams.module.agent.controller;

import com.pams.common.*;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.service.*;
import com.pams.module.agent.orchestration.*;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.security.LoginUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/agent")
public class AgentRuntimeController {
    private final AgentRequestService requests;
    private final AgentEventPublisher publisher;
    private final WorkItemState state;
    private final DagScheduler scheduler;
    private final AgentTaskRepository tasks;
    private final TaskGate gate;
    private final ConversationStore conversations;
    private final AgentRunEventRepository events;
    public AgentRuntimeController(AgentRequestService requests,AgentEventPublisher publisher,WorkItemState state,DagScheduler scheduler,
                                  AgentTaskRepository tasks,TaskGate gate,ConversationStore conversations,AgentRunEventRepository events) {
        this.requests=requests;this.publisher=publisher;this.state=state;this.scheduler=scheduler;this.tasks=tasks;this.gate=gate;this.conversations=conversations;this.events=events;
    }
    @PostMapping("/requests/{id}/abort")
    public Result<Map<String,Object>> abortRequest(@PathVariable String id,@AuthenticationPrincipal LoginUser user) {
        var request=requests.cancel(id,user.getId());String status=request.getStatus();
        if(request.getRunId()!=null) status=state.cancel(request.getRunId(),user.getId());
        if(request.getStreamId()!=null) {publisher.markAborted(request.getStreamId());gate.cancelUnsubmitted(request.getStreamId(),user.getId());}
        var out=new LinkedHashMap<String,Object>();out.put("requestId",id);out.put("aborted",request.isCancelled());out.put("status",status);
        out.put("runId",request.getRunId());return Result.ok(out);
    }
    @PostMapping("/team/runs/{id}/cancel")
    public Result<Map<String,Object>> cancelRun(@PathVariable Long id,@AuthenticationPrincipal LoginUser user) {
        var run=state.owned(id,user.getId());state.cancel(id,user.getId());
        if(run.getRequestId()!=null) requests.cancel(run.getRequestId(),user.getId());
        publisher.markAborted(run.getStreamId());return Result.ok(scheduler.view(id,ToolContext.current(null,null)));
    }
    @GetMapping("/requests/{id}")
    public Result<Map<String,Object>> request(@PathVariable String id,@AuthenticationPrincipal LoginUser user) {
        var request=requests.owned(id,user.getId());
        if(!requests.live(request)) throw new BizException(403,"请求权限已变化，请基于当前权限重新发起");
        var out=new LinkedHashMap<String,Object>();out.put("requestId",id);out.put("streamId",request.getStreamId());out.put("conversationId",request.getConversationId());
        out.put("runId",request.getRunId());out.put("status",request.getStatus());out.put("cancelled",request.isCancelled());
        if(request.getRunId()!=null) out.put("run",scheduler.view(request.getRunId(),ToolContext.current(null,null)));
        if(request.getStreamId()!=null) out.put("actions",tasks.findByStreamIdAndUserIdOrderByIdAsc(request.getStreamId(),user.getId()).stream().map(t->gate.detail(t.getId(),user.getId())).toList());
        if(request.getConversationId()!=null) {
            var page=conversations.messages(request.getConversationId(),user.getId(),1,100,ToolContext.current(request.getConversationId(),request.getStreamId()));
            out.put("message",page.getRecords().stream().filter(m->"ASSISTANT".equals(m.get("role")) && Objects.equals(request.getStreamId(),m.get("streamId"))).reduce((a,b)->b).orElse(null));
            if(out.get("message")!=null && "RUNNING".equals(request.getStatus())) out.put("status","SUCCESS");
        }
        if("RUNNING".equals(request.getStatus()) && request.getRunId()==null && request.getUpdatedAt().isBefore(RuntimeIdentity.STARTED) && out.get("message")==null) {
            requests.finish(request.getStreamId(),"INTERRUPTED");out.put("status","INTERRUPTED");
        }
        return Result.ok(out);
    }
    @GetMapping("/team/runs/{id}/events")
    public Result<Map<String,Object>> events(@PathVariable Long id,@RequestParam(defaultValue="0")long since,@AuthenticationPrincipal LoginUser user) {
        scheduler.snapshot(id,ToolContext.current(null,null));var run=state.owned(id,user.getId());
        long cursor=Math.max(0,since);
        var page=events.findByRunIdAndEventSeqGreaterThanOrderByEventSeqAsc(id,cursor,PageRequest.of(0,200));
        boolean gap=cursor>run.getEventSeq() || (page.isEmpty()?cursor<run.getEventSeq():page.getFirst().getEventSeq()!=cursor+1);
        var rows=page.stream().map(e->Map.of("eventSeq",e.getEventSeq(),"runId",id,"status",e.getStatus())).toList();
        return Result.ok(Map.of("events",rows,"requiresSnapshot",gap,"lastSeq",page.isEmpty()?run.getEventSeq():page.getLast().getEventSeq(),"hasMore",!page.isEmpty() && page.getLast().getEventSeq()<run.getEventSeq()));
    }
}
