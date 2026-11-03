package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.TaskProgressActionService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PrepareTaskProgressUpdateTool implements WriteAgentTool {
    private final TaskProgressActionService progress;
    public PrepareTaskProgressUpdateTool(TaskProgressActionService progress){this.progress=progress;}
    public String name(){return "prepareTaskProgressUpdate";}public String label(){return "任务进度修改";}
    public String runningLabel(){return "正在准备任务进度修改…";}
    public String description(){return "准备明确任务的进度修改预览，确认后才保存。不支持FORM；先queryActivityTasks读取taskId和revision，缺目标先消歧。仅改0–100整数进度，状态由业务规则计算；不改负责人、时间、依赖。进度权限只能改本人负责的任务，管理权限限当前组织。过期revision须重读预览，不盲重试；成功回执不代表通知已投递。";}
    public List<String> requiredPermissions(){return List.of("task:progress","task:manage");}public boolean anyPermission(){return true;}
    public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("taskId",ToolArgs.prop("integer","明确任务ID"),"expectedRevision",ToolArgs.prop("integer","查询获得的revision"),"progress",ToolArgs.prop("integer","0–100整数进度")),List.of("taskId","expectedRevision","progress"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){return progress.prepare(ctx,args);}
    public CommitResult commit(ToolContext ctx,AgentActionPlan action){try{return progress.commit(ctx,action);}catch(com.pams.common.BizException e){return CommitResult.failed(e.getMessage());}catch(Exception e){return new CommitResult("RESULT_UNKNOWN",0,0,"任务进度提交结果待核对，请核对回执，不要重复提交。",Map.of(),List.of());}}
}
