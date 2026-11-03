package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentSchedulePublicationService;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class PrepareSchedulePublicationTool implements WriteAgentTool {
    private final AgentSchedulePublicationService service;public PrepareSchedulePublicationTool(AgentSchedulePublicationService service){this.service=service;}
    public String name(){return "prepareSchedulePublication";}public String label(){return "准备指定班次发布";}public String runningLabel(){return "正在准备班次发布预览…";}
    public List<String> requiredPermissions(){return List.of("activity:view","schedule:view","schedule:publish");}public Sensitivity sensitivity(){return Sensitivity.SENSITIVE;}
    public String description(){return "先queryActivitySchedules读取明确班次ID和基线，准备发布1–20个DRAFT活动班次。scheduleIds明确非空，baselines按ID字符串映射读取基线；不默认publish-all。确认时重查班次与人员及权限，调用原生发布、考勤和通知；发布不等于通知已送达。";}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("scheduleIds",ToolArgs.map("type","array","items",ToolArgs.prop("integer","明确班次ID"),"maxItems",20),"baselines",ToolArgs.map("type","object","description","班次ID字符串到读取基线的映射")),List.of("scheduleIds","baselines"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){try{return service.prepare(ctx,args);}catch(com.pams.common.BizException e){return ToolResult.failed(e.getMessage());}}
    public CommitResult commit(ToolContext ctx,AgentActionPlan plan){try{return service.commit(ctx,plan);}catch(com.pams.common.BizException e){return CommitResult.failed(e.getMessage());}catch(Exception e){return new CommitResult("RESULT_UNKNOWN",0,0,"发布结果待核对，不重复发布。",Map.of(),List.of());}}
}
