package com.pams.module.agent.tool;

import com.pams.module.agent.core.*;
import com.pams.module.notification.service.PendingWorkService;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class QueryMyPendingWorkTool implements AgentTool {
  private final PendingWorkService pending;
  public QueryMyPendingWorkTool(PendingWorkService pending){this.pending=pending;}
  @Override public String name(){return "queryMyPendingWork";}
  @Override public String label(){return "待我处理";}
  @Override public String runningLabel(){return "正在汇总待你处理的事项…";}
  @Override public String description(){return "汇总本人任务、选定审核人的策划书、可办理推文和本人已发布排班；按当前权限和业务状态查询，与工作台及通知中心一致。";}
  @Override public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",Map.of(),List.of());}
  @Override public List<String> requiredPermissions(){return List.of("task:view","plan:review","content:view","schedule:view","schedule:self:view","notice:view");}
  @Override public boolean anyPermission(){return true;}
  @Override public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
  @Override public ToolResult execute(ToolContext ctx,Map<String,Object> args) {
    var summary=pending.summary(ctx);
    List<Map<String,Object>> rows=new ArrayList<>(),refs=new ArrayList<>();
    for(var item:summary.items()) {
      var row=new LinkedHashMap<String,Object>();row.put("id",item.entityId());row.put("name",item.title());row.put("type",item.type());
      row.put("action",item.action());row.put("endDate",item.dueAt()==null?null:item.dueAt().toString());row.put("overdue",item.overdue());row.put("progress",item.progress());
      rows.add(row);
      String link=item.entityType().equals("ANNOUNCEMENT")?"/archive/announcements?id="+item.entityId():item.entityType().equals("SCHEDULE")?(ctx.hasPermission("schedule:view")?"/routine/activity-schedules":"/routine/my-schedules"):
        item.activityId()!=null?"/activities/"+item.activityId()+"?tab="+switch(item.entityType()){case "PLAN"->"plan";case "ARTICLE"->"articles";default->"gantt";}:"/content/articles";
      refs.add(ToolArgs.map("type",item.entityType().toLowerCase(Locale.ROOT),"id",item.entityId(),"label",item.title(),"link",link));
    }
    var payload=new LinkedHashMap<String,Object>();payload.put("mineCount",summary.total());payload.put("overdueCount",summary.overdue());
    payload.put("overdueMineCount",summary.overdue());payload.put("total",summary.total());payload.put("counts",summary.counts());payload.put("items",summary.items());
    payload.put("table",ToolArgs.table(new String[]{"事项","类型","需办理","截止","逾期"},rows));payload.put("page","pending");
    return ToolResult.ok(summary.total()==0?"当前没有待你处理的事项。":"待你处理的有 "+summary.total()+" 项，其中 "+summary.overdue()+" 项已逾期。",payload,refs);
  }
}
