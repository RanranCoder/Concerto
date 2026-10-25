package com.pams.module.agent.stream;

import com.pams.common.OrgContext;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.orchestration.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j @Service
public class AgentRunOutbox {
    private final AgentRunEventRepository events;
    private final AgentRunRepository runs;
    private final AgentTaskRepository tasks;
    private final WorkItemState state;
    private final DagScheduler scheduler;
    private final AgentEventPublisher publisher;
    private final TenantAuthorizationService authorization;
    private final AgentProperties props;
    private final Environment environment;
    private long scanCursor;
    public AgentRunOutbox(AgentRunEventRepository events,AgentRunRepository runs,AgentTaskRepository tasks,WorkItemState state,
                          DagScheduler scheduler,AgentEventPublisher publisher,TenantAuthorizationService authorization,AgentProperties props,Environment environment) {
        this.events=events;this.runs=runs;this.tasks=tasks;this.state=state;this.scheduler=scheduler;this.publisher=publisher;this.authorization=authorization;this.props=props;this.environment=environment;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { tick(); }
    @Scheduled(fixedDelayString="${pams.agent.outbox-poll-ms:2000}",initialDelay=5000)
    public void tick() {
        if(!props.isRuntimeRecoveryEnabled() || environment.matchesProfiles("test")) return;
        try { reconcile(); deliverOnce(); } catch(Exception e) { log.warn("协作恢复扫描失败，将重试",e); }
    }
    /** Exposed for deterministic recovery tests. Metadata only; every tenant is restored explicitly. */
    public synchronized void reconcile() {
        var batch=runs.recoverable(scanCursor,PageRequest.of(0,50));
        if(batch.isEmpty()) {scanCursor=0;return;}
        for(var run:batch) {
            scanCursor=run.getId();Long previous=OrgContext.get();OrgContext.set(run.getOrgId());
            try {
                var nodes=state.nodes(run.getId()); if(nodes.isEmpty()) continue;
                boolean staleLease=run.getDispatchToken()!=null && (run.getDispatchStartedAt()!=null && run.getDispatchStartedAt().isBefore(LocalDateTime.now().minusMinutes(30))
                    || run.getDispatchOwner()!=null && !RuntimeIdentity.ID.equals(run.getDispatchOwner()));
                boolean mismatch=false;
                for(var n:nodes) if(n.getActionTaskId()!=null) {
                    var a=tasks.findById(n.getActionTaskId()).orElseThrow();
                    boolean unknown=AgentTask.RUNNING.equals(a.getStatus()) && (a.getExecutionOwner()!=null && !RuntimeIdentity.ID.equals(a.getExecutionOwner())
                        || a.getExecutionOwner()==null && a.getConfirmedAt()!=null && a.getConfirmedAt().isBefore(RuntimeIdentity.STARTED));
                    String status=unknown?"NEEDS_RECONCILE":AgentTask.PENDING_CONFIRM.equals(a.getStatus())?"WAITING_CONFIRM":a.getStatus();
                    if(!status.equals(n.getStatus())) mismatch=true;
                }
                if(staleLease || mismatch) state.recover(run.getId(),run.getUserId());
            } catch(Exception e) { log.warn("任务回执核对未完成：run={}",run.getId(),e); }
            finally { if(previous==null)OrgContext.clear();else OrgContext.set(previous); }
        }
    }
    public void deliverOnce() {
        for(var event:events.pending(LocalDateTime.now(),PageRequest.of(0,50))) deliver(event);
    }
    private void deliver(AgentRunEvent event) {
        String token=UUID.randomUUID().toString();LocalDateTime now=LocalDateTime.now();
        if(events.claim(event.getId(),token,now,now.plusSeconds(60))==0) return;
        Long previous=OrgContext.get();OrgContext.set(event.getOrgId());
        try {
            AgentRun run=state.owned(event.getRunId(),event.getUserId());
            if(!authorization.valid(event.getUserId()) || !Objects.equals(run.getOrgId(),((Number)authorization.identity(event.getUserId()).get("org_id")).longValue()) || !authorization.authorities(event.getUserId()).containsAll(state.authority(run))) {
                events.delivered(event.getId(),token,LocalDateTime.now());return;
            }
            if(event.isWakeRequired() && !run.isCancelRequested() && state.ready(run.getId()).isPresent()) scheduler.drive(run.getId(),run.getUserId());
            var identity=authorization.identity(event.getUserId());
            // User name is looked up at delivery, never trusted from the persisted outbox.
            var current=authorization.refresh(new com.pams.module.agent.core.ToolContext(
                new com.pams.security.LoginUser(run.getUserId(),"","","",0,null,null,run.getOrgId()),List.of(),run.getConversationId(),run.getStreamId(),run.getToolMode()));
            Map<String,Object> notice=new LinkedHashMap<>();notice.put("type","AGENT_RUN_UPDATED");notice.put("runId",run.getId());notice.put("conversationId",run.getConversationId());
            notice.put("streamId",run.getStreamId());notice.put("requestId",run.getRequestId());notice.put("eventSeq",event.getEventSeq());notice.put("status",event.getStatus());notice.put("protocolVersion",2);
            publisher.publishDurable(current.getLoginUser().getUsername(),notice);
            events.delivered(event.getId(),token,LocalDateTime.now());
        } catch(Exception e) { log.warn("关键事件投递失败，保留待重试：event={}",event.getId(),e); }
        finally { if(previous==null)OrgContext.clear();else OrgContext.set(previous); }
    }
}
