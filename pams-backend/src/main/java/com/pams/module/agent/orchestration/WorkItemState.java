package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.service.AgentJson;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Short, run-locked transactions only. No LLM or business write runs under this lock. */
@Service
public class WorkItemState {
    public record Changed(Long runId, Long userId, Long orgId) {}
    private final AgentRunRepository runs;
    private final AgentWorkItemRepository items;
    private final AgentStepRepository steps;
    private final AgentTaskRepository tasks;
    private final AgentMessageRepository messages;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json = new ObjectMapper();
    private final PolicyGuard policy = new PolicyGuard();
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.registry.AgentRegistry agentRegistry;
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.config.AgentProperties agentProperties;
    @org.springframework.beans.factory.annotation.Autowired private AgentRunEventRepository runEvents;
    @org.springframework.beans.factory.annotation.Autowired private AgentReviewIssueRepository reviewIssues;
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.service.AgentRequestService requests;

    public WorkItemState(AgentRunRepository runs, AgentWorkItemRepository items, AgentStepRepository steps,
                         AgentTaskRepository tasks, AgentMessageRepository messages, ApplicationEventPublisher events) {
        this.runs=runs; this.items=items; this.steps=steps; this.tasks=tasks; this.messages=messages; this.events=events;
    }
    public AgentRun owned(Long id, Long uid) {
        AgentRun run=runs.findByIdAndUserId(id,uid).orElseThrow(()->new BizException(4004,"协作任务不存在"));
        if (!Objects.equals(run.getOrgId(),OrgContext.get())) throw new BizException(403,"组织上下文不匹配");
        return run;
    }
    private AgentRun lock(Long id) {
        AgentRun run=runs.lockById(id).orElseThrow(()->new BizException(4004,"协作任务不存在"));
        if (!Objects.equals(run.getOrgId(),OrgContext.get())) throw new BizException(403,"组织上下文不匹配");
        return run;
    }
    public List<AgentWorkItem> nodes(Long runId) { return items.findByRunIdOrderByNodeKeyAsc(runId); }
    public List<Integer> dependencies(AgentWorkItem item) {
        try { return json.convertValue(AgentJson.readTree(json,item.getDependenciesJson()),new TypeReference<List<Integer>>(){}); }
        catch(Exception e) { throw new IllegalStateException("任务依赖损坏",e); }
    }
    public Set<String> authority(AgentRun run) {
        try { return json.convertValue(AgentJson.readTree(json,run.getAuthorityJson()),new TypeReference<Set<String>>(){}); }
        catch(Exception e) { throw new BizException(4400,"任务上下文缺失，请重新发起"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); }
    }

    public static void validate(List<Blackboard.PlanItem> plan) {
        Map<Integer,Blackboard.PlanItem> byKey=new HashMap<>();
        for(var n:plan) if(n.getId()<1 || byKey.put(n.getId(),n)!=null) throw new BizException(4400,"任务编号重复或无效");
        Set<Integer> done=new HashSet<>();
        while(done.size()<plan.size()) {
            int before=done.size();
            for(var n:plan) {
                if(n.getDependsOn()==null || !byKey.keySet().containsAll(n.getDependsOn())) throw new BizException(4400,"任务依赖不存在");
                if(done.containsAll(n.getDependsOn())) done.add(n.getId());
            }
            if(done.size()==before) throw new BizException(4400,"任务依赖包含环");
        }
    }
    @Transactional
    public void initialize(Long runId, Blackboard bb, ToolContext ctx) {
        validate(bb.getPlan());
        AgentRun run=lock(runId);
        if(!Objects.equals(run.getUserId(),ctx.getUserId())) throw new BizException(403,"无权执行任务");
        if(!nodes(runId).isEmpty()) throw new BizException(4400,"任务已初始化");
        run.setStreamId(ctx.getStreamId()); run.setToolMode(ctx.getMode()); run.setAuthorityJson(encode(ctx.getAuthorities()));
        run.setRequestId(ctx.getRequestId());
        for(var p:bb.getPlan()) {
            var n=new AgentWorkItem(); n.setRunId(runId); n.setNodeKey(p.getId()); n.setAgentName(p.getAgent());
            n.setInstruction(p.getTask()); n.setDependenciesJson(encode(p.getDependsOn())); n.setUpdatedAt(LocalDateTime.now());
            n.setContractJson(p.getContract()==null?null:encode(p.getContract()));
            items.save(n);
        }
        update(run,true);
    }
    @Transactional
    public boolean acquire(Long id,String token) {
        AgentRun run=lock(id);
        if(run.getDispatchToken()!=null || run.isCancelRequested()) return false;
        run.setDispatchToken(token); run.setDispatchOwner(RuntimeIdentity.ID); run.setDispatchStartedAt(LocalDateTime.now()); return true;
    }
    @Transactional
    public void release(Long id,String token) {
        AgentRun run=lock(id);
        if(Objects.equals(token,run.getDispatchToken())) { run.setDispatchToken(null); run.setDispatchOwner(null); run.setDispatchStartedAt(null); update(run); }
    }
    public Optional<AgentWorkItem> ready(Long id) {
        if(runs.findById(id).map(AgentRun::isCancelRequested).orElse(true)) return Optional.empty();
        List<AgentWorkItem> all=nodes(id);
        Set<Integer> done=new HashSet<>();
        all.stream().filter(n->"SUCCESS".equals(n.getStatus())).forEach(n->done.add(n.getNodeKey()));
        return all.stream().filter(n->"QUEUED".equals(n.getStatus()) && done.containsAll(dependencies(n))).findFirst();
    }
    @Transactional
    public AgentWorkItem begin(Long id,Long nodeId,String token) {
        AgentRun run=lock(id);
        requireActive(run);
        if(!Objects.equals(token,run.getDispatchToken())) throw new BizException(4400,"调度权已变化");
        AgentWorkItem n=items.findById(nodeId).orElseThrow();
        if(!Objects.equals(n.getRunId(),id) || ready(id).filter(x->x.getId().equals(nodeId)).isEmpty()) throw new BizException(4400,"任务暂不可执行");
        n.setStatus("RUNNING"); n.setAttempt(n.getAttempt()+1); n.setUpdatedAt(LocalDateTime.now());
        items.saveAndFlush(n);
        AgentStep step=new AgentStep(); step.setRunId(id); step.setWorkItemId(n.getId()); step.setAgentName(n.getAgentName());
        step.setRevision(n.getRevision()); step.setAttempt(n.getAttempt()); step.setStatus("RUNNING");
        step.setSeq((int)steps.countByRunId(id)+1); step.setCreatedAt(LocalDateTime.now());
        var input=new LinkedHashMap<String,Object>();input.put("instruction",n.getInstruction());input.put("dependsOn",dependencies(n));
        if(n.getContractJson()!=null)input.put("contract",ControlledBindings.tree(n.getContractJson()));
        step.setInputJson(encode(input)); steps.save(step);
        update(run); return n;
    }
    /** Called inside the same transaction as gate creation: a token always belongs to one revision. */
    @Transactional
    public void attach(AgentTask action) {
        if(action.getWorkItemId()==null) return;
        AgentRun run=lock(action.getRunId());
        requireActive(run);
        AgentWorkItem n=items.findById(action.getWorkItemId()).orElseThrow();
        if(!Objects.equals(n.getRunId(),run.getId()) || n.getRevision()!=action.getWorkItemRevision()
                || !"RUNNING".equals(n.getStatus()) || n.getActionTaskId()!=null) throw new BizException(4400,"任务版本已变化");
        n.setActionTaskId(action.getId()); n.setStatus(nodeStatus(action.getStatus()));
        items.saveAndFlush(n); update(run);
    }
    @Transactional
    public int claimAction(AgentTask action,ToolContext ctx) {
        AgentRun run=lock(action.getRunId());
        requireActive(run);
        if(!ctx.getAuthorities().containsAll(authority(run))) throw new BizException(403,"任务所需权限已变化，请重新发起");
        AgentWorkItem n=items.findById(action.getWorkItemId()).orElseThrow();
        if(!Objects.equals(n.getRunId(),run.getId()) || n.getRevision()!=action.getWorkItemRevision()
                || !Objects.equals(n.getActionTaskId(),action.getId())) throw new BizException(4400,"确认清单已失效");
        if(n.getContractJson()!=null){
            if(!agentProperties.isControlledExecutionEnabled() || !agentProperties.isControlledPlanningEnabled() || !agentProperties.getControlledPlanningOrgIds().contains(run.getOrgId()))throw new BizException(409,"本组织受控执行已关闭");
            var contract=ControlledBindings.tree(n.getContractJson());
            var definition=agentRegistry.byName(n.getAgentName()).filter(d->Integer.valueOf(1).equals(d.getEnabled())).orElseThrow(()->new BizException(403,"专家已停用"));
            var scope=agentRegistry.toolScopeOf(definition);
            if(!action.getToolName().equals(contract.path("capability").asText()) || scope!=null&&!scope.contains(action.getToolName()))throw new BizException(403,"节点能力已撤销");
        }
        if("BLOCKED".equals(n.getReviewStatus()) || reviewIssues.existsByWorkItemIdAndRevisionAndAttemptAndSeverity(n.getId(),n.getRevision(),n.getAttempt(),"BLOCKING")) throw new BizException(4400,"请先修订审查问题");
        return tasks.claimOwned(action.getId(),AgentTask.PENDING_CONFIRM,AgentTask.RUNNING,LocalDateTime.now(),RuntimeIdentity.ID);
    }
    @Transactional
    public void complete(AgentWorkItem attempted,SpecialistAgent.Result result,long duration,String token) {
        complete(attempted,result,duration,token,null);
    }
    @Transactional
    public void complete(AgentWorkItem attempted,SpecialistAgent.Result result,long duration,String token,String receipt) {
        AgentRun run=lock(attempted.getRunId());
        if(!Objects.equals(run.getDispatchToken(),token)) throw new BizException(4400,"该执行尝试已失效");
        AgentWorkItem n=items.findById(attempted.getId()).orElseThrow();
        if(n.getRevision()!=attempted.getRevision() || n.getAttempt()!=attempted.getAttempt()) throw new BizException(4400,"执行版本已过期");
        n.setResultJson(receipt);
        String stored=policy.forStorage(result.sensitivity(),result.text(),"敏感步骤结果未在任务记录中留存，请在对应业务页核对。");
        if(n.getActionTaskId()==null) { n.setStatus(run.isCancelRequested()?"CANCELLED":result.state()); n.setOutputText(stored); }
        else {
            AgentTask a=tasks.findById(n.getActionTaskId()).orElseThrow();
            n.setStatus(nodeStatus(a.getStatus()));
            n.setOutputText(a.getResultJson()==null?stored:storedAction(a));
        }
        n.setUpdatedAt(LocalDateTime.now()); items.saveAndFlush(n);
        AgentStep s=steps.findByWorkItemIdAndRevisionAndAttempt(n.getId(),n.getRevision(),n.getAttempt()).orElseThrow();
        if(!"RUNNING".equals(s.getStatus())) throw new BizException(4400,"执行尝试已结算");
        var facts=new ArrayList<String>();int factChars=0;
        if(result.sensitivity()==Sensitivity.NORMAL)for(var invocation:result.invocations()) {
            String fact=invocation.summary();
            if(!"OK".equals(invocation.status()) || fact==null || !fact.startsWith("以下是工具返回的数据"))continue;
            int jsonStart=fact.indexOf('{');if(jsonStart<0)continue;
            fact=fact.substring(jsonStart);
            try {if(!AgentJson.readTree(json,fact).isObject())continue;}catch(Exception invalid){continue;}
            if(factChars+fact.length()>10000)continue;
            facts.add(fact);factChars+=fact.length();
        }
        s.setOutputJson(encode(Map.of("text",Objects.toString(stored,""),"state",result.state(),"toolFacts",facts,"structuredReceipt",receipt==null?Map.of():ControlledBindings.tree(receipt),"invocations",result.invocations().stream().map(i->Map.of("name",i.name(),"status",i.status(),"durationMs",i.durationMs())).toList())));
        s.setStatus(n.getStatus()); s.setDurationMs((int)duration); steps.save(s);
        update(run);
    }
    private String nodeStatus(String status) {
        if("RESULT_UNKNOWN".equals(status)) return "NEEDS_RECONCILE";
        return AgentTask.PENDING_CONFIRM.equals(status)?"WAITING_CONFIRM":status;
    }
    /** Only actual normal tool data or a committed business receipt is authoritative review evidence. */
    public String sourceFacts(AgentWorkItem node) {
        if(node.getActionTaskId()!=null) {
            var action=tasks.findById(node.getActionTaskId()).orElseThrow();
            if(Set.of(AgentTask.SUCCESS,AgentTask.PARTIAL).contains(action.getStatus()) && sensitivity(action)==Sensitivity.NORMAL && action.getResultJson()!=null)return storedAction(action);
        }
        return steps.findByWorkItemIdAndRevisionAndAttempt(node.getId(),node.getRevision(),node.getAttempt()).map(step->{
            try {
                var facts=AgentJson.readTree(json,step.getOutputJson()).path("toolFacts");
                if(!facts.isArray())return "";var text=new StringBuilder();for(var fact:facts)text.append(fact.asText()).append('\n');return text.toString();
            }catch(Exception ignored){return "";}
        }).orElse("");
    }

    /** Explicit recovery only. A potentially committed RUNNING business action is never replayed. */
    @Transactional
    public void recover(Long runId,Long userId) {
        owned(runId,userId); AgentRun run=lock(runId);
        if(run.getDispatchToken()!=null) {
            boolean previousProcess=run.getDispatchOwner()!=null && !RuntimeIdentity.ID.equals(run.getDispatchOwner()) && run.getDispatchStartedAt()!=null && run.getDispatchStartedAt().isBefore(RuntimeIdentity.STARTED);
            if(!previousProcess && run.getDispatchStartedAt()!=null && run.getDispatchStartedAt().isAfter(LocalDateTime.now().minusMinutes(30))) return;
            run.setDispatchToken(null); run.setDispatchOwner(null); run.setDispatchStartedAt(null);
            for(var n:nodes(runId)) if("RUNNING".equals(n.getStatus()) && n.getActionTaskId()==null) {
                n.setStatus("FAILED"); n.setOutputText("执行中断，可手动重试此步骤。"); items.save(n);
                steps.findByWorkItemIdAndRevisionAndAttempt(n.getId(),n.getRevision(),n.getAttempt()).ifPresent(s->{
                    if("RUNNING".equals(s.getStatus())) { s.setStatus("FAILED"); s.setOutputJson(encode(Map.of("text","执行中断"))); steps.save(s); }
                });
            }
        }
        for(var n:nodes(runId)) if(n.getActionTaskId()!=null) {
            var action=tasks.findById(n.getActionTaskId()).orElseThrow();
            n.setStatus(uncertain(action)?"NEEDS_RECONCILE":nodeStatus(action.getStatus()));
            if(action.getResultJson()!=null) n.setOutputText(storedAction(action));
            items.save(n);
        }
        items.flush(); update(run,true);
    }
    @Transactional
    public String settle(AgentTask action) {
        AgentRun run=lock(action.getRunId());
        AgentWorkItem n=items.findById(action.getWorkItemId()).orElseThrow();
        AgentTask fresh=tasks.findById(action.getId()).orElseThrow();
        if(n.getRevision()==action.getWorkItemRevision() && Objects.equals(n.getActionTaskId(),action.getId())) {
            n.setStatus(nodeStatus(fresh.getStatus()));
            if(fresh.getResultJson()!=null) n.setOutputText(storedAction(fresh));
            n.setUpdatedAt(LocalDateTime.now()); items.saveAndFlush(n); update(run,true);
            events.publishEvent(new Changed(run.getId(),run.getUserId(),run.getOrgId()));
        }
        return run.getStatus();
    }
    /** Retry a preview/read failure only; any submitted action (including FAILED/PARTIAL) is never replayed. */
    @Transactional
    public void retry(Long runId,Long nodeId,Long userId) {
        owned(runId,userId); AgentRun run=lock(runId);
        requireActive(run);
        if(run.getDispatchToken()!=null) throw new BizException(4400,"任务仍在执行中");
        var n=nodes(runId).stream().filter(x->x.getId().equals(nodeId)).findFirst().orElseThrow(()->new BizException(4004,"节点不存在"));
        if(!Set.of("FAILED","PARTIAL").contains(n.getStatus()) || n.getActionTaskId()!=null) throw new BizException(4400,"仅可重试未提交业务动作的失败节点");
        n.setStatus("QUEUED"); n.setReviewStatus("NOT_REVIEWED"); n.setOutputText(null); items.saveAndFlush(n); update(run,true);
    }
    /** Revision invalidates only this node and its transitive dependants, keeping attempt history. */
    @Transactional
    public void revise(Long runId,Long nodeId,Long userId,int revision,String instruction) {
        var target=nodes(runId).stream().filter(n->n.getId().equals(nodeId)).findFirst().orElse(null);
        if(target!=null && target.getContractJson()!=null)throw new BizException(409,"受控节点须通过服务端规划句柄修订");
        reviseBranch(runId,nodeId,userId,revision,instruction);
    }
    @Transactional
    public void reviseContract(Long runId,Long nodeId,Long userId,int revision,Blackboard.PlanItem replacement) {
        reviseBranch(runId,nodeId,userId,revision,replacement.getTask());
        var node=items.findById(nodeId).orElseThrow();node.setContractJson(encode(replacement.getContract()));items.saveAndFlush(node);
    }
    private void reviseBranch(Long runId,Long nodeId,Long userId,int revision,String instruction) {
        owned(runId,userId); AgentRun run=lock(runId);
        requireActive(run);
        if(run.getDispatchToken()!=null || instruction==null || instruction.isBlank() || instruction.length()>16000) throw new BizException(4400,"执行中不可修改，或修订内容无效");
        List<AgentWorkItem> all=nodes(runId);
        var root=all.stream().filter(n->n.getId().equals(nodeId)).findFirst().orElseThrow(()->new BizException(4004,"节点不存在"));
        if(root.getRevision()!=revision) throw new BizException(4400,"节点版本已更新，请刷新");
        Set<Integer> affected=new HashSet<>(Set.of(root.getNodeKey()));
        int previous;
        do { previous=affected.size(); for(var n:all) if(dependencies(n).stream().anyMatch(affected::contains)) affected.add(n.getNodeKey()); } while(previous!=affected.size());
        var selected=all.stream().filter(n->affected.contains(n.getNodeKey())).toList();
        for(var n:selected) {
            if("RUNNING".equals(n.getStatus())) throw new BizException(4400,"节点仍在执行中");
            if(n.getActionTaskId()!=null) {
                var a=tasks.findById(n.getActionTaskId()).orElseThrow();
                if(!Set.of(AgentTask.WAITING_INPUT,AgentTask.PENDING_CONFIRM,AgentTask.CANCELLED,"REVIEW_REQUIRED").contains(a.getStatus()))
                    throw new BizException(4400,"该分支已有业务提交，须先核对结果再发起新任务");
            }
        }
        for(var n:selected) {
            if(n.getActionTaskId()!=null && tasks.cancelPending(n.getActionTaskId(),userId,LocalDateTime.now())==0) {
                var fresh=tasks.findById(n.getActionTaskId()).orElseThrow();
                if(!AgentTask.CANCELLED.equals(fresh.getStatus())) throw new BizException(409,"该分支已有业务提交，请刷新核对结果");
            }
            n.setRevision(n.getRevision()+1); n.setStatus("QUEUED"); n.setOutputText(null); n.setActionTaskId(null);
            n.setResultJson(null);
            n.setReviewStatus("NOT_REVIEWED");
            if(n.getId().equals(nodeId)) n.setInstruction(instruction);
            items.save(n);
        }
        items.flush(); update(run,true);
    }
    @Transactional
    public void lockForEdit(AgentTask task,Long uid) {
        owned(task.getRunId(),uid);var run=lock(task.getRunId());requireActive(run);
        var node=items.findById(task.getWorkItemId()).orElseThrow();
        if(run.getDispatchToken()!=null || !Objects.equals(node.getActionTaskId(),task.getId()) || node.getRevision()!=task.getWorkItemRevision()) throw new BizException(409,"任务正在执行或版本已变化，请稍后重试");
    }
    @Transactional
    public ToolContext prepareEditedPreview(AgentTask task,ToolContext current) {
        lockForEdit(task,current.getUserId());var old=items.findById(task.getWorkItemId()).orElseThrow();
        revise(task.getRunId(),old.getId(),current.getUserId(),old.getRevision(),old.getInstruction());
        var node=items.findById(old.getId()).orElseThrow();node.setStatus("RUNNING");node.setAttempt(node.getAttempt()+1);items.saveAndFlush(node);
        var step=new AgentStep();step.setRunId(task.getRunId());step.setWorkItemId(node.getId());step.setRevision(node.getRevision());step.setAttempt(node.getAttempt());
        step.setAgentName(node.getAgentName());step.setStatus("SUCCESS");step.setSeq((int)steps.countByRunId(task.getRunId())+1);step.setCreatedAt(LocalDateTime.now());step.setInputJson(encode(Map.of("origin","USER_EDIT")));steps.save(step);
        return current.derive(task.getConversationId(),task.getStreamId()).withRunId(task.getRunId()).withWorkItem(node.getId(),node.getRevision()).withAttempt(node.getAttempt());
    }
    public record Review(String status, List<Map<String,Object>> issues) {}
    public Map<String,Object> reviews(Long runId) {
        Map<String,Object> out=new LinkedHashMap<>();
        var all=reviewIssues.findByRunIdOrderByIdAsc(runId);
        for(var n:nodes(runId)) {
            var rows=all.stream().filter(i->Objects.equals(i.getWorkItemId(),n.getId()) && i.getRevision()==n.getRevision() && i.getAttempt()==n.getAttempt()).map(i->{
                Map<String,Object> row=new LinkedHashMap<>();row.put("id",i.getId());row.put("revision",i.getRevision());row.put("attempt",i.getAttempt());
                row.put("severity",i.getSeverity());row.put("code",i.getCode());row.put("location",i.getLocation());row.put("quote",i.getQuoteText());row.put("reason",i.getReason());row.put("sourceRef",i.getSourceRef());return row;
            }).toList();
            out.put(n.getId().toString(),Map.of("status",n.getReviewStatus(),"issues",rows));
        }
        return out;
    }
    @Transactional
    public void review(ToolContext ctx, Review result) {
        AgentRun run=lock(ctx.getRunId()); requireActive(run);
        var n=items.findById(ctx.getWorkItemId()).orElseThrow();
        if(!Objects.equals(n.getRunId(),run.getId()) || n.getRevision()!=ctx.getWorkItemRevision() || ctx.getAttempt()!=null && n.getAttempt()!=ctx.getAttempt()) throw new BizException(4400,"审查版本已过期");
        n.setReviewStatus(result.status());items.saveAndFlush(n);
        for(var row:result.issues()) {
            var issue=new AgentReviewIssue();issue.setRunId(run.getId());issue.setWorkItemId(n.getId());issue.setRevision(n.getRevision());issue.setAttempt(n.getAttempt());
            issue.setSeverity(Objects.toString(row.get("severity"),"WARNING"));issue.setCode(Objects.toString(row.get("code"),"CONTENT"));
            issue.setLocation(Objects.toString(row.get("location"),"正文"));issue.setQuoteText(Objects.toString(row.get("quote"),""));
            issue.setReason(Objects.toString(row.get("reason"),"请核对原文"));issue.setSourceRef(Objects.toString(row.get("sourceRef"),""));issue.setCreatedAt(LocalDateTime.now());reviewIssues.save(issue);
        }
        update(run);
    }
    private void update(AgentRun run) {
        update(run,false);
    }
    private void update(AgentRun run,boolean wake) {
        var all=nodes(run.getId());
        String status=overall(run,all);
        run.setStatus(status); run.setFinishedAt(status.startsWith("WAITING_") || Set.of("RUNNING","STOPPING","NEEDS_RECONCILE","REVIEW_REQUIRED").contains(status)?null:LocalDateTime.now());
        run.setBlackboardJson(encode(blackboard(run,all)));
        run.setEventSeq(run.getEventSeq()+1); runs.save(run);
        var event=new AgentRunEvent();event.setRunId(run.getId());event.setOrgId(run.getOrgId());event.setUserId(run.getUserId());
        event.setEventSeq(run.getEventSeq());event.setStatus(status);event.setWakeRequired(wake);event.setCreatedAt(LocalDateTime.now());runEvents.save(event);
        if(run.getStreamId()!=null) requests.link(run.getStreamId(),run.getConversationId(),run.getId());
        if(run.getStreamId()!=null) for(var m:messages.findByStreamIdAndRole(run.getStreamId(),"ASSISTANT")) {
            m.setExecutionState(status); messages.save(m);
        }
    }
    static String aggregate(List<AgentWorkItem> all) {
        if(all.isEmpty()) return "FAILED";
        if(all.stream().anyMatch(n->"RUNNING".equals(n.getStatus()))) return "RUNNING";
        if(all.stream().anyMatch(n->"NEEDS_RECONCILE".equals(n.getStatus()))) return "NEEDS_RECONCILE";
        if(all.stream().anyMatch(n->"REVIEW_REQUIRED".equals(n.getStatus()))) return "REVIEW_REQUIRED";
        if(all.stream().anyMatch(n->"WAITING_INPUT".equals(n.getStatus()))) return "WAITING_INPUT";
        if(all.stream().anyMatch(n->"WAITING_CONFIRM".equals(n.getStatus()))) return "WAITING_CONFIRM";
        Set<Integer> done=new HashSet<>(); all.stream().filter(n->"SUCCESS".equals(n.getStatus())).forEach(n->done.add(n.getNodeKey()));
        // Runnable nodes keep the run active. Blocked descendants remain QUEUED for a targeted retry.
        if(all.stream().anyMatch(n->"QUEUED".equals(n.getStatus()) && done.containsAll(parseDependencies(n)))) return "RUNNING";
        if(all.stream().allMatch(n->"SUCCESS".equals(n.getStatus()))) return "SUCCESS";
        if(all.stream().allMatch(n->"CANCELLED".equals(n.getStatus()))) return "CANCELLED";
        return all.stream().anyMatch(n->Set.of("SUCCESS","PARTIAL").contains(n.getStatus()))?"PARTIAL":"FAILED";
    }
    private static List<Integer> parseDependencies(AgentWorkItem n) {
        try { var mapper=new ObjectMapper(); return mapper.convertValue(AgentJson.readTree(mapper,n.getDependenciesJson()),new TypeReference<List<Integer>>(){}); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public Blackboard blackboard(AgentRun run,List<AgentWorkItem> all) {
        var bb=new Blackboard(); bb.setGoal(run.getGoal()); bb.setMode(run.getMode()); bb.setPersistedState(overall(run,all));
        for(var n:all) {
            var p=new Blackboard.PlanItem(); p.setId(n.getNodeKey()); p.setAgent(n.getAgentName()); p.setTask(n.getInstruction());
            if(n.getContractJson()!=null)p.setContract(json.convertValue(ControlledBindings.tree(n.getContractJson()),new TypeReference<Map<String,Object>>(){}));
            p.setStatus(n.getStatus()); p.setDependsOn(dependencies(n)); p.setWorkItemId(n.getId()); p.setRevision(n.getRevision()); p.setAttempt(n.getAttempt()); bb.getPlan().add(p);
            if(n.getOutputText()!=null) bb.addFinding(n.getAgentName(),n.getOutputText(),null);
        }
        return bb;
    }
    private String overall(AgentRun run,List<AgentWorkItem> all) {
        if(!run.isCancelRequested()) return aggregate(all);
        if(all.stream().anyMatch(n->"NEEDS_RECONCILE".equals(n.getStatus()))) return "NEEDS_RECONCILE";
        if(all.stream().anyMatch(n->"RUNNING".equals(n.getStatus()))) return "STOPPING";
        return all.stream().anyMatch(n->"SUCCESS".equals(n.getStatus()) || "PARTIAL".equals(n.getStatus()))?"PARTIAL":"CANCELLED";
    }
    private void requireActive(AgentRun run) {
        if(run.isCancelRequested() || run.getStreamId()!=null && requests.byStream(run.getStreamId()).map(AgentRequest::isCancelled).orElse(false))
            throw new java.util.concurrent.CancellationException("本次执行已停止");
    }
    private boolean uncertain(AgentTask action) {
        return "RESULT_UNKNOWN".equals(action.getStatus()) || AgentTask.RUNNING.equals(action.getStatus()) && (action.getExecutionOwner()!=null && !RuntimeIdentity.ID.equals(action.getExecutionOwner())
            || action.getConfirmedAt()!=null && action.getConfirmedAt().isBefore(RuntimeIdentity.STARTED) && action.getExecutionOwner()==null);
    }
    @Transactional
    public String cancel(Long id,Long uid) {
        owned(id,uid);var run=lock(id);
        if(Set.of("SUCCESS","CANCELLED").contains(run.getStatus())) return run.getStatus();
        run.setCancelRequested(true);
        for(var n:nodes(id)) {
            if(n.getActionTaskId()!=null) {
                var a=tasks.findById(n.getActionTaskId()).orElseThrow();
                if("RESULT_UNKNOWN".equals(a.getStatus())) {n.setStatus("NEEDS_RECONCILE");items.save(n);continue;}
                if(AgentTask.RUNNING.equals(a.getStatus())) { n.setStatus(uncertain(a)?"NEEDS_RECONCILE":"RUNNING");items.save(n);continue; }
                if(Set.of(AgentTask.SUCCESS,AgentTask.FAILED,AgentTask.PARTIAL).contains(a.getStatus())) continue;
                tasks.cancelPending(a.getId(),uid,LocalDateTime.now());
            }
            if(!Set.of("SUCCESS","FAILED","PARTIAL","RUNNING").contains(n.getStatus())) { n.setStatus("CANCELLED");items.save(n); }
        }
        items.flush();update(run);return run.getStatus();
    }
    private String normalize(String value) {
        try { return encode(AgentJson.readTree(json,value)); }
        catch(Exception e) { throw new IllegalStateException("业务结果损坏，请核对记录",e); }
    }
    public static Sensitivity sensitivity(AgentTask action) {
        try { return Sensitivity.valueOf(action.getDataSensitivity()); }
        catch(Exception e) { return Sensitivity.RESTRICTED; }
    }
    private String storedAction(AgentTask action) {
        return policy.forStorage(sensitivity(action),normalize(action.getResultJson()),"敏感业务回执未在任务记录中复制，请在对应业务页核对。");
    }
}
