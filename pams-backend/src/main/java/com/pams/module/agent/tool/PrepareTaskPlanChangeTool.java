package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentTaskPlanService;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class PrepareTaskPlanChangeTool implements WriteAgentTool {
    private final AgentTaskPlanService plans;public PrepareTaskPlanChangeTool(AgentTaskPlanService plans){this.plans=plans;}
    public String name(){return "prepareTaskPlanChange";}public String label(){return "任务计划变更";}public String runningLabel(){return "正在校验任务计划变更…";}
    public String description(){return "为明确活动准备单项任务新增或字段修改预览；确认后保存，无FORM。先queryActivityTasks读取planBaseline，修改还须taskId与expectedRevision。changes仅传授权字段：name、deptId、assigneeUserId、startDate/endDate（ISO日期成对）、dependsOn、isMilestone、priority、description。缺字段不清空旧值，不支持删除、进度、状态、级联或跨活动调整。关联日期冲突回原生计划处理；同名新增不重复追加。";}
    public List<String> requiredPermissions(){return List.of("task:manage");}public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("activityId",ToolArgs.prop("integer","活动ID"),"taskId",ToolArgs.prop("integer","修改目标；新增省略"),"expectedRevision",ToolArgs.prop("integer","修改时目标revision"),"planBaseline",ToolArgs.prop("string","查询获得的整体计划基线"),"changes",Map.of("type","object","properties",ToolArgs.map("name",ToolArgs.prop("string","任务名称"),"deptId",ToolArgs.prop("integer","部门ID"),"assigneeUserId",ToolArgs.prop("integer","唯一账号ID"),"startDate",ToolArgs.prop("string","ISO开始日期"),"endDate",ToolArgs.prop("string","ISO结束日期"),"dependsOn",ToolArgs.prop("integer","前置任务ID"),"isMilestone",ToolArgs.prop("integer","0或1"),"priority",ToolArgs.prop("integer","优先级"),"description",ToolArgs.prop("string","说明")),"additionalProperties",false)),List.of("activityId","planBaseline","changes"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){try{return plans.prepare(ctx,args);}catch(com.pams.common.BizException e){return ToolResult.failed(e.getMessage());}catch(Exception e){return ToolResult.failed("任务变更参数无效，请核对字段与日期");}}
    public CommitResult commit(ToolContext ctx,AgentActionPlan action){try{return plans.commit(ctx,action);}catch(com.pams.common.BizException e){return CommitResult.failed(e.getMessage());}catch(Exception e){return new CommitResult("RESULT_UNKNOWN",0,0,"任务计划提交结果待核对，不要重复执行。",Map.of(),List.of());}}
}
