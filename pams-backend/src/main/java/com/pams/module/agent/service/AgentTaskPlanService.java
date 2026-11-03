package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.*;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.TaskService;
import com.pams.module.agent.core.*;
import com.pams.module.agent.tool.ToolArgs;
import com.pams.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AgentTaskPlanService {
    public static final String KIND="prepareTaskPlanChange";
    private static final Set<String> FIELDS=Set.of("name","deptId","assigneeUserId","startDate","endDate","dependsOn","isMilestone","priority","description");
    private final AgentPreparedOperations ops;private final ActivityRepository activities;private final TaskRepository tasks;
    private final TaskService service;private final UserRepository users;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public AgentTaskPlanService(AgentPreparedOperations ops,ActivityRepository activities,TaskRepository tasks,TaskService service,UserRepository users){this.ops=ops;this.activities=activities;this.tasks=tasks;this.service=service;this.users=users;}
    private Activity activity(ToolContext ctx,Long id,boolean write){ops.guard(ctx,"task:manage",write);return activities.findById(id).filter(a->Objects.equals(a.getOrgId(),ctx.getLoginUser().getOrgId())).orElseThrow(()->new BizException(404,"活动不存在或不可见"));}
    public static String baseline(List<Task> tasks){return PlanDocumentReader.hash(tasks.stream().sorted(Comparator.comparing(Task::getId)).map(t->t.getId()+":"+Objects.requireNonNullElse(t.getRevision(),0L)).collect(java.util.stream.Collectors.joining(",")));}
    private void assignee(TaskRequest request,ToolContext ctx) {
        if(request.getAssigneeUserId()!=null && users.findById(request.getAssigneeUserId()).filter(u->Objects.equals(u.getOrgId(),ctx.getLoginUser().getOrgId()) && Integer.valueOf(1).equals(u.getStatus())).isEmpty()) throw new BizException(404,"负责人账号不存在或不可见");
    }
    @Transactional
    public ToolResult prepare(ToolContext ctx,Map<String,Object> args) throws Exception {
        return prepareInternal(ctx,args,KIND);
    }
    @Transactional
    public ToolResult prepareGenerated(ToolContext ctx,Map<String,Object> args) throws Exception {return prepareInternal(ctx,args,"generateActivityTasks");}
    @Transactional
    public ToolResult prepareBatch(ToolContext ctx,Long aid,String expected,List<?> suggestions,boolean onlyMissing)throws Exception{
        var a=activity(ctx,aid,true);var locked=activities.lockById(aid).orElseThrow();em.refresh(locked);
        var all=service.listByActivity(aid);all.forEach(em::refresh);String baseline=baseline(all);
        if(!baseline.equals(expected))throw new BizException(409,"STALE_BASELINE：任务计划已变化，请重新查询");
        if(suggestions.isEmpty() || suggestions.size()>10)throw new BizException(400,"任务建议每批1–10项，不会静默忽略超出项");
        var requests=new ArrayList<TaskRequest>();var items=new ArrayList<AgentActionPlan.PlanItem>();var skipped=new ArrayList<AgentActionPlan.SkippedItem>();var seen=new HashSet<String>();
        for(Object suggestion:suggestions){
            if(!(suggestion instanceof Map<?,?> fields) || !FIELDS.containsAll(fields.keySet()))throw new BizException(400,"任务建议含不支持字段");
            var req=ops.json.convertValue(fields,TaskRequest.class);req.setActivityId(aid);
            if(req.getName()==null || req.getName().isBlank() || req.getName().length()>100 || req.getDescription()!=null&&req.getDescription().length()>2000)throw new BizException(400,"任务名称/说明无效");
            req.setName(req.getName().trim());if(!seen.add(req.getName()))throw new BizException(409,"同批建议存在重名任务，请明确区分");
            if(all.stream().anyMatch(t->Objects.equals(t.getName(),req.getName()))){if(onlyMissing){skipped.add(new AgentActionPlan.SkippedItem(req.getName(),"不追加","已有同名任务"));continue;}throw new BizException(409,"已有同名任务，请指定任务ID修改，不能重复追加");}
            assignee(req,ctx);var preview=service.preview(new TaskPlan.Request(null,req,null,false));
            if(!preview.impacts().isEmpty())throw new BizException(409,"CONFLICT：建议影响现有任务，请在原生界面处理");
            requests.add(req);items.add(new AgentActionPlan.PlanItem(null,req.getName(),Objects.toString(req.getStartDate(),"未设日期")+" → "+Objects.toString(req.getEndDate(),"未设日期")));
        }
        if(requests.isEmpty())return ToolResult.ok("所有建议均已有同名任务，无需重复生成。");
        String id=ops.create(ctx,"generateActivityTasks",Map.of("activityId",aid,"baseline",baseline,"batch",requests));
        var raw=ops.plan(id,"活动「"+a.getName()+"」任务建议（"+requests.size()+"项）",items,ops.encode(requests),"活动ID："+aid,"计划基线："+baseline,"整批采用或取消；确认后原子保存，不设置未经定位的负责人。" );
        return ToolResult.plan("已准备真实任务建议预览，尚未写入；已有同名项已跳过。",new AgentActionPlan(null,raw.summary(),raw.total(),raw.items(),skipped,raw.detailLines(),false,null,raw.preview()));
    }
    private ToolResult prepareInternal(ToolContext ctx,Map<String,Object> args,String kind) throws Exception {
        Long aid=ToolArgs.lng(args,"activityId"),id=ToolArgs.lng(args,"taskId");if(aid==null) throw new BizException(400,"需要明确activityId");
        var a=activity(ctx,aid,true);var locked=activities.lockById(aid).orElseThrow();em.refresh(locked);
        var all=service.listByActivity(aid);all.forEach(em::refresh);
        if(!baseline(all).equals(ToolArgs.str(args,"planBaseline"))) throw new BizException(409,"STALE_BASELINE：任务计划已变化，请重新查询");
        if(!(args.get("changes") instanceof Map<?,?> raw) || raw.isEmpty() || !FIELDS.containsAll(raw.keySet())) throw new BizException(400,"只允许显式任务字段变更；不支持删除、进度或状态改写");
        var patch=new LinkedHashMap<String,Object>();raw.forEach((k,v)->patch.put(k.toString(),v));
        if(patch.containsKey("assigneeUserId") && patch.get("assigneeUserId")==null)throw new BizException(400,"首版不支持清空负责人，请在原生界面处理");
        var req=new TaskRequest();Task old=null;
        if(id!=null){old=all.stream().filter(t->t.getId().equals(id)).findFirst().orElseThrow(()->new BizException(404,"任务不存在或不可见"));
            if(!Objects.equals(old.getRevision(),ToolArgs.lng(args,"expectedRevision"))) throw new BizException(409,"STALE_BASELINE：目标任务已变化");
            org.springframework.beans.BeanUtils.copyProperties(old,req);req.setStatus(old.getStatus().name());req.setExpectedRevision(old.getRevision());
        }
        req.setActivityId(aid);ops.json.readerForUpdating(req).readValue(ops.encode(patch));assignee(req,ctx);
        if(req.getName()==null || req.getName().length()>100 || req.getDescription()!=null && req.getDescription().length()>2000) throw new BizException(400,"任务名称/说明无效或过长");
        if(id==null && all.stream().anyMatch(t->Objects.equals(t.getName(),req.getName().trim()))) throw new BizException(409,"存在同名任务，请指定任务ID修改或明确区分名称");
        var request=new TaskPlan.Request(id,req,null,false);var preview=service.preview(request);
        if(!preview.impacts().isEmpty()) throw new BizException(409,"CONFLICT：变更将影响下游任务日期，请在原生任务计划界面处理关联影响");
        var persisted=new TaskPlan.Request(id,req,preview.token(),false);
        String operation=ops.create(ctx,kind,Map.of("request",persisted,"before",old==null?Map.of():fields(old),"after",fields(preview.candidate())));
        String diff="原值：\n"+ops.encode(old==null?Map.of():fields(old))+"\n\n拟保存：\n"+ops.encode(fields(preview.candidate()));
        return ToolResult.plan("已准备单项任务计划变更，尚未保存。",ops.plan(operation,"活动「"+a.getName()+"」任务"+(id==null?"新增":"修改"),List.of(new AgentActionPlan.PlanItem(id,req.getName(),id==null?"新增":"修改")),diff,
                "活动ID："+aid+"；任务ID："+Objects.toString(id,"新建"),"计划基线："+preview.token(),"仅采用这项变更；无删除、撤销或自动级联。"));
    }
    private Map<String,Object> fields(Task t){return ToolArgs.map("name",t.getName(),"deptId",t.getDeptId(),"assigneeUserId",t.getAssigneeUserId(),"startDate",t.getStartDate(),"endDate",t.getEndDate(),"dependsOn",t.getDependsOn(),"isMilestone",t.getIsMilestone(),"priority",t.getPriority(),"description",t.getDescription(),"progress",t.getProgress());}
    @Transactional(rollbackFor=Exception.class)
    public WriteAgentTool.CommitResult commit(ToolContext ctx,AgentActionPlan action) throws Exception {
        return commitInternal(ctx,action,KIND);
    }
    @Transactional(rollbackFor=Exception.class)
    public WriteAgentTool.CommitResult commitGenerated(ToolContext ctx,AgentActionPlan action) throws Exception{return commitInternal(ctx,action,"generateActivityTasks");}
    private WriteAgentTool.CommitResult commitInternal(ToolContext ctx,AgentActionPlan action,String kind) throws Exception {
        ops.guard(ctx,"task:manage",true);var op=ops.require(ctx,action,kind,true);
        if(op.payload().containsKey("batch"))return commitBatch(ctx,op);
        var req=ops.json.convertValue(op.payload().get("request"),TaskPlan.Request.class);
        var a=activity(ctx,req.task().getActivityId(),true);
        if(op.result()!=null)return op.result();
        var locked=activities.lockById(a.getId()).orElseThrow();em.refresh(locked);service.listByActivity(a.getId()).forEach(em::refresh);assignee(req.task(),ctx);
        var current=service.preview(req);if(!current.impacts().isEmpty() || !Objects.equals(req.previewToken(),current.token())) throw new BizException(409,"STALE_BASELINE：任务计划已变化，请重新预览");
        var saved=service.savePlan(req,ctx.getUserId());Long resultId=req.id();
        if(resultId==null) resultId=saved.undo().entries().stream().filter(e->e.before()==null).map(TaskPlan.UndoEntry::id).findFirst().orElseThrow();
        var target=tasks.findById(resultId).orElseThrow();
        var result=new WriteAgentTool.CommitResult("SUCCESS",1,0,"已保存单项任务计划变更，关联通知由业务服务处理。",ToolArgs.map("taskId",resultId,"activityId",a.getId(),"revision",target.getRevision()),List.of(ToolArgs.map("type","task","id",resultId,"label",target.getName(),"link","/activities/"+a.getId()+"?tab=tasks")));
        ops.finish(op.id(),result);return result;
    }
    private WriteAgentTool.CommitResult commitBatch(ToolContext ctx,AgentPreparedOperations.Operation op){
        Long aid=((Number)op.payload().get("activityId")).longValue();activity(ctx,aid,true);if(op.result()!=null)return op.result();
        var a=activities.lockById(aid).orElseThrow();em.refresh(a);var all=service.listByActivity(aid);all.forEach(em::refresh);
        if(!baseline(all).equals(op.payload().get("baseline")))throw new BizException(409,"STALE_BASELINE：任务计划已变化，请重新预览");
        var requests=ops.json.convertValue(op.payload().get("batch"),new com.fasterxml.jackson.core.type.TypeReference<List<TaskRequest>>(){});
        var refs=new ArrayList<Map<String,Object>>();var ids=new ArrayList<Long>();
        for(var req:requests){assignee(req,ctx);var preview=service.preview(new TaskPlan.Request(null,req,null,false));
            if(!preview.impacts().isEmpty())throw new BizException(409,"任务关联影响已变化");
            var saved=service.savePlan(new TaskPlan.Request(null,req,preview.token(),false),ctx.getUserId());
            Long id=saved.undo().entries().stream().filter(e->e.before()==null).map(TaskPlan.UndoEntry::id).findFirst().orElseThrow();ids.add(id);
            refs.add(ToolArgs.map("type","task","id",id,"label",req.getName(),"link","/activities/"+aid+"?tab=tasks"));
        }
        var result=new WriteAgentTool.CommitResult("SUCCESS",ids.size(),0,"已原子保存任务建议，通知由业务服务处理。",ToolArgs.map("activityId",aid,"taskIds",ids),refs);ops.finish(op.id(),result);return result;
    }
    @Transactional(readOnly=true)
    public Optional<WriteAgentTool.CommitResult> confirmedReceipt(ToolContext ctx,AgentActionPlan action,String kind){ops.guard(ctx,"task:manage",false);var op=ops.require(ctx,action,kind,false);
        if(op.payload().containsKey("batch")){activity(ctx,((Number)op.payload().get("activityId")).longValue(),false);if(op.result()!=null){var ids=ops.json.convertValue(op.result().detail().get("taskIds"),new com.fasterxml.jackson.core.type.TypeReference<List<Long>>(){});if(tasks.findAllById(ids).size()!=ids.size())throw new BizException(404,"回执关联任务不可见");}return Optional.ofNullable(op.result());}
        var req=ops.json.convertValue(op.payload().get("request"),TaskPlan.Request.class);activity(ctx,req.task().getActivityId(),false);
        if(op.result()!=null && tasks.findById(((Number)op.result().detail().get("taskId")).longValue()).isEmpty())throw new BizException(404,"回执关联任务不可见");return Optional.ofNullable(op.result());}
}
