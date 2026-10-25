package com.pams.module.agent.service;

import com.pams.common.*;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentRequest;
import com.pams.module.agent.repository.AgentRequestRepository;
import com.pams.module.permission.service.TenantAuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AgentRequestService {
    private final AgentRequestRepository requests;
    private final JdbcTemplate db;
    private final com.pams.module.agent.repository.AgentTaskRepository tasks;
    private final TenantAuthorizationService authorization;
    public AgentRequestService(AgentRequestRepository requests,JdbcTemplate db,TenantAuthorizationService authorization,com.pams.module.agent.repository.AgentTaskRepository tasks) {
        this.requests=requests; this.db=db; this.authorization=authorization; this.tasks=tasks;
    }
    private void validate(String id) {
        if(id==null || !id.matches("[A-Za-z0-9_-]{8,64}")) throw new BizException(400,"请求标识无效");
    }
    private void ensure(String id,Long userId) {
        validate(id);
        if(userId==null || OrgContext.get()==null || !authorization.valid(userId)) throw new BizException(403,"发起人身份失效");
        long authVersion=((Number)authorization.identity(userId).get("authz_version")).longValue();
        db.update("insert into agent_request(id,user_id,org_id,authz_version,status,cancelled,updated_at) values(?,?,?,?,'CREATED',false,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE id=id",id,userId,OrgContext.get(),authVersion);
    }
    private AgentRequest check(AgentRequest r,Long uid) {
        if(!Objects.equals(uid,r.getUserId()) || !Objects.equals(OrgContext.get(),r.getOrgId())) throw new BizException(404,"请求不存在");
        return r;
    }
    public AgentRequest owned(String id,Long uid) {
        return check(requests.findById(id).orElseThrow(()->new BizException(404,"请求不存在")),uid);
    }
    @Transactional
    public AgentRequest begin(String id,String stream,ToolContext ctx) {
        ensure(id,ctx.getUserId());
        var r=check(requests.lockById(id).orElseThrow(),ctx.getUserId());
        if(r.getStreamId()!=null) throw new BizException(409,"该请求已经受理，请恢复原请求状态");
        r.setStreamId(stream); r.setConversationId(ctx.getConversationId()); r.setStatus(r.isCancelled()?"CANCELLED":"RUNNING");
        r.setUpdatedAt(LocalDateTime.now()); return requests.save(r);
    }
    @Transactional
    public AgentRequest cancel(String id,Long uid) {
        ensure(id,uid);
        var r=check(requests.lockById(id).orElseThrow(),uid);
        boolean unsettled=r.getStreamId()!=null && tasks.findByStreamIdAndUserIdOrderByIdAsc(r.getStreamId(),uid).stream().anyMatch(t->Set.of("PENDING_CONFIRM","WAITING_INPUT","REVIEW_REQUIRED","RUNNING").contains(t.getStatus()));
        if(unsettled || !Set.of("SUCCESS","FAILED","CANCELLED","INTERRUPTED").contains(r.getStatus())) {
            r.setCancelled(true); r.setStatus("STOPPING"); r.setUpdatedAt(LocalDateTime.now()); requests.save(r);
        }
        return r;
    }
    @Transactional
    public void link(String stream,Long conversationId,Long runId) {
        requests.lockByStreamId(stream).ifPresent(r->{
            if(conversationId!=null) r.setConversationId(conversationId);
            if(runId!=null) r.setRunId(runId);
            r.setUpdatedAt(LocalDateTime.now()); requests.save(r);
        });
    }
    @Transactional
    public void finish(String stream,String status) {
        requests.lockByStreamId(stream).ifPresent(r->{
            r.setStatus(r.isCancelled()?"CANCELLED":status); r.setUpdatedAt(LocalDateTime.now()); requests.save(r);
        });
    }
    @Transactional
    public void requireActive(String stream) {
        if(stream==null) return;
        requests.lockByStreamId(stream).ifPresent(r->{if(r.isCancelled()) throw new java.util.concurrent.CancellationException("本次请求已停止");});
    }
    @Transactional
    public int claimOrdinary(com.pams.module.agent.entity.AgentTask task) {
        requireActive(task.getStreamId());
        return tasks.claimOwned(task.getId(),"PENDING_CONFIRM","RUNNING",LocalDateTime.now(),com.pams.module.agent.orchestration.RuntimeIdentity.ID);
    }
    public Optional<AgentRequest> byStream(String stream) { return requests.findByStreamId(stream); }
    public boolean live(AgentRequest r) {
        return authorization.valid(r.getUserId()) && Objects.equals(r.getOrgId(),((Number)authorization.identity(r.getUserId()).get("org_id")).longValue())
            && r.getAuthzVersion()==((Number)authorization.identity(r.getUserId()).get("authz_version")).longValue();
    }
}
