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
    public record Instruction(String id, String text) {}
    public record SteeringBatch(int through, List<Instruction> inputs) {
        public boolean pending() { return !inputs.isEmpty(); }
    }
    private static final int MAX_INSTRUCTIONS = 8;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
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
            r.setStatus(r.isCancelled()?"CANCELLED":status); r.setSteeringOpen(false);
            r.setUpdatedAt(LocalDateTime.now()); requests.save(r);
        });
    }

    @Transactional
    public void openSteering(String stream) {
        requests.lockByStreamId(stream).ifPresent(request -> {
            if (request.isCancelled()) throw new java.util.concurrent.CancellationException();
            if ("RUNNING".equals(request.getStatus()) && request.getRunId() == null) request.setSteeringOpen(true);
        });
    }

    @Transactional
    public int appendInstruction(String requestId, Long userId, String inputId, String text) {
        validate(requestId);
        validate(inputId);
        String content = text == null ? "" : text.strip();
        if (content.isEmpty() || content.length() > 2000) throw new BizException(400, "补充指令须为 1 至 2000 字");
        var request = check(requests.lockById(requestId).orElseThrow(() -> new BizException(404, "请求不存在")), userId);
        if (!live(request)) throw new BizException(403, "请求权限已变化");
        var entries = new ArrayList<>(instructions(request));
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            if (entry.id().equals(inputId)) {
                if (!entry.text().equals(content)) throw new BizException(409, "补充指令标识已使用");
                return index + 1;
            }
        }
        if (!request.isSteeringOpen() || request.isCancelled() || !"RUNNING".equals(request.getStatus())
                || request.getUpdatedAt().isBefore(com.pams.module.agent.orchestration.RuntimeIdentity.STARTED)) {
            throw new BizException(409, "当前任务无法接收补充指令，请在结束后发送");
        }
        if (entries.size() >= MAX_INSTRUCTIONS) throw new BizException(409, "本轮补充指令已达上限");
        entries.add(new Instruction(inputId, content));
        try {
            String stored = json.writeValueAsString(entries);
            if (stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) {
                throw new BizException(409, "本轮补充指令总量已达上限");
            }
            request.setSteeringJson(stored);
            request.setUpdatedAt(LocalDateTime.now());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法保存补充指令", e);
        }
        return entries.size();
    }

    public List<Instruction> instructions(AgentRequest request) {
        if (request.getSteeringJson() == null) return List.of();
        try {
            return json.readValue(request.getSteeringJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法读取补充指令", e);
        }
    }

    /** Atomically checks for new input before handing control back to the user. */
    @Transactional
    public List<Instruction> steeringAfter(String stream, int cursor, boolean sealIfEmpty) {
        var request = requests.lockByStreamId(stream).orElse(null);
        if (request == null) return List.of();
        if (request.isCancelled()) throw new java.util.concurrent.CancellationException();
        var entries = instructions(request);
        if (cursor >= entries.size()) {
            if (sealIfEmpty) request.setSteeringOpen(false);
            return List.of();
        }
        return List.copyOf(entries.subList(cursor, entries.size()));
    }

    /** Reads the durable DAG cursor. Only a run bound to the currently active turn can accept corrections. */
    @Transactional
    public SteeringBatch collaborativeSteering(String stream, Long runId, boolean sealIfEmpty) {
        var request = stream == null ? null : requests.lockByStreamId(stream).orElse(null);
        if (request == null || !Objects.equals(request.getRunId(), runId) || !request.isSteeringOpen()
                || request.isCancelled() || !"RUNNING".equals(request.getStatus())) {
            return new SteeringBatch(0, List.of());
        }
        var entries = instructions(request);
        int through = entries.size();
        if (through <= request.getSteeringConsumed()) {
            if (sealIfEmpty) request.setSteeringOpen(false);
            return new SteeringBatch(through, List.of());
        }
        return new SteeringBatch(through, List.copyOf(entries.subList(request.getSteeringConsumed(), through)));
    }

    @Transactional
    public void closeSteering(String stream) {
        if (stream != null) requests.lockByStreamId(stream).ifPresent(request -> request.setSteeringOpen(false));
    }

    @Transactional
    public void markSteeringConsumed(String stream, int cursor) {
        requests.lockByStreamId(stream).ifPresent(request ->
                request.setSteeringConsumed(Math.max(request.getSteeringConsumed(), cursor)));
    }

    public Optional<AgentRequest> forMessage(String stream, Long conversationId, ToolContext ctx) {
        if (stream == null || ctx == null || ctx.getLoginUser() == null) return Optional.empty();
        return byStream(stream).filter(request -> Objects.equals(request.getConversationId(), conversationId)
                && Objects.equals(request.getUserId(), ctx.getUserId())
                && Objects.equals(request.getOrgId(), ctx.getLoginUser().getOrgId()));
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
