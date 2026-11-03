package com.pams.module.agent.service;
import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.tool.ToolArgs;
import com.pams.module.routine.entity.*;
import com.pams.module.routine.repository.*;
import com.pams.module.routine.service.ActivityScheduleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AgentSchedulePublicationService {
    public static final String KIND="prepareSchedulePublication";
    private final AgentPreparedOperations ops;private final ScheduleRepository schedules;private final SchedulePersonRepository people;
    private final ActivityScheduleService service;private final com.pams.module.activity.repository.ActivityRepository activities;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public AgentSchedulePublicationService(AgentPreparedOperations ops,ScheduleRepository schedules,SchedulePersonRepository people,ActivityScheduleService service,com.pams.module.activity.repository.ActivityRepository activities){this.ops=ops;this.schedules=schedules;this.people=people;this.service=service;this.activities=activities;}
    private Schedule require(ToolContext ctx,Long id,boolean lock){
        var s=(lock?schedules.lockById(id):schedules.findById(id)).orElseThrow(()->new BizException(404,"班次不存在或不可见"));if(lock)em.refresh(s);
        if(!Objects.equals(s.getOrgId(),ctx.getLoginUser().getOrgId())||!Schedule.TYPE_ACTIVITY.equals(s.getScheduleType())||s.getActivityId()==null||activities.findById(s.getActivityId()).filter(a->Objects.equals(a.getOrgId(),ctx.getLoginUser().getOrgId())).isEmpty())throw new BizException(404,"活动班次不存在或不可见");return s;
    }
    private String baseline(Schedule s){
        var persons=people.findByScheduleId(s.getId()).stream().sorted(Comparator.comparing(SchedulePerson::getId)).map(p->ToolArgs.map("id",p.getId(),"userId",p.getUserId(),"name",p.getPersonName(),"primary",p.getIsPrimary())).toList();
        return PlanDocumentReader.hash(ops.encode(ToolArgs.map("id",s.getId(),"revision",s.getRevision(),"activityId",s.getActivityId(),"date",s.getScheduleDate(),"start",s.getStartTime(),"end",s.getEndTime(),"status",s.getStatus(),"location",s.getLocation(),"notes",s.getNotes(),"persons",persons)));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> metadata(ToolContext ctx,Long id){ops.guard(ctx,"schedule:view",false);var s=require(ctx,id,false);return ToolArgs.map("revision",s.getRevision(),"baseline",baseline(s),"personCount",people.findByScheduleId(id).size());}
    private List<Long> selected(Object raw){if(!(raw instanceof List<?> ids)||ids.isEmpty()||ids.size()>20)throw new BizException(400,"须明确1–20个班次ID，不默认全量发布");var result=new TreeSet<Long>();for(Object id:ids){if(id==null||!id.toString().matches("[1-9][0-9]{0,17}")||!result.add(Long.valueOf(id.toString())))throw new BizException(400,"班次ID无效或重复");}return List.copyOf(result);}
    private void guard(ToolContext ctx,boolean write){ops.guard(ctx,"activity:view",write);ops.guard(ctx,"schedule:view",write);ops.guard(ctx,"schedule:publish",write);}
    @Transactional
    public ToolResult prepare(ToolContext ctx,Map<String,Object> args){
        guard(ctx,true);var ids=selected(args.get("scheduleIds"));if(!(args.get("baselines") instanceof Map<?,?> expected)||expected.size()!=ids.size())throw new BizException(400,"须提供每个班次的读取基线");
        var frozen=new LinkedHashMap<String,String>();var items=new ArrayList<AgentActionPlan.PlanItem>();var preview=new StringBuilder("仅发布以下明确班次；将按原生服务预建考勤并触发业务通知，发布不等于通知已送达。\n");
        for(Long id:ids){var s=require(ctx,id,false);String hash=baseline(s);if(!hash.equals(expected.get(id.toString()))||!Schedule.STATUS_DRAFT.equals(s.getStatus()))throw new BizException(409,"班次基线或草稿状态已变化");
            frozen.put(id.toString(),hash);items.add(new AgentActionPlan.PlanItem(id,"班次",Objects.toString(s.getScheduleDate(),"")+" "+s.getStartTime()+"–"+s.getEndTime()));
            preview.append("班次 ").append(id).append("：").append(s.getScheduleDate()).append(' ').append(s.getStartTime()).append('–').append(s.getEndTime()).append("，地点：").append(Objects.toString(s.getLocation(),"未填")).append("，人员：");
            preview.append(String.join("、",people.findByScheduleId(id).stream().map(SchedulePerson::getPersonName).map(n->Objects.toString(n,"")).toList())).append('\n');
        }
        String op=ops.create(ctx,KIND,Map.of("scheduleIds",ids,"baselines",frozen));return ToolResult.plan("已准备指定班次发布预览，尚未发布。",ops.plan(op,"发布指定活动班次",items,preview.toString(),"确认时重新核对完整班次与人员基线；不会默认发布其他班次。"));
    }
    @Transactional(rollbackFor=Exception.class)
    public WriteAgentTool.CommitResult commit(ToolContext ctx,AgentActionPlan action){
        guard(ctx,true);var op=ops.require(ctx,action,KIND,true);if(op.result()!=null){verifyVisible(ctx,op.result());return op.result();}
        var ids=selected(op.payload().get("scheduleIds"));var expected=(Map<?,?>)op.payload().get("baselines");
        var activityIds=new TreeSet<Long>();
        for(Long id:ids){var s=require(ctx,id,true);if(!Schedule.STATUS_DRAFT.equals(s.getStatus())||!baseline(s).equals(expected.get(id.toString())))throw new BizException(409,"班次或人员已变化，请重新读取预览");activityIds.add(s.getActivityId());}
        for(Long id:ids)service.publish(id);schedules.flush();
        var references=activityIds.stream().map(id->ToolArgs.map("type","activity","id",id,"label","活动排班","link","/routine/activity-schedules?activityId="+id)).toList();
        var result=new WriteAgentTool.CommitResult("SUCCESS",ids.size(),0,"已发布明确班次并调用原生考勤及通知流程；不表示通知已送达或已阅读。",ToolArgs.map("scheduleIds",ids,"status","PUBLISHED","operationId",op.id()),references);ops.finish(op.id(),result);return result;
    }
    private void verifyVisible(ToolContext ctx,WriteAgentTool.CommitResult result){for(Long id:selected(result.detail().get("scheduleIds")))require(ctx,id,false);}
    @Transactional(readOnly=true)
    public Optional<WriteAgentTool.CommitResult> receipt(ToolContext ctx,AgentActionPlan action){guard(ctx,false);var op=ops.require(ctx,action,KIND,false);if(op.result()!=null)verifyVisible(ctx,op.result());return Optional.ofNullable(op.result());}
}
