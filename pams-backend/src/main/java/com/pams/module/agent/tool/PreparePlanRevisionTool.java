package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.PlanRevisionService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PreparePlanRevisionTool implements WriteAgentTool {
    private final PlanRevisionService revisions;
    public PreparePlanRevisionTool(PlanRevisionService revisions) {this.revisions=revisions;}
    public String name(){return "preparePlanRevision";}
    public String label(){return "预算定向修订";}
    public String runningLabel(){return "正在校验预算修订与原文差异…";}
    public String description(){return "仅为用户当前明确要求‘只把预算总额改为3000元，其余保留’准备单处金额变更预览；用户金额可不同。不支持通用润色、表格、分项调整或旧HTML。必须先完整读取当前原生DOCX，revisionEligible=true；传回真实planId/documentRef/baseline及目标完整块的blockId/hash/text。after必须只替换授权数字，不能重建全文。预览不写策划书，确认后创建新草稿；无FORM。";}
    public List<String> requiredPermissions(){return List.of("activity:view","plan:view","plan:edit");}
    public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map(
            "planId",ToolArgs.prop("integer","指定当前版本ID"),"documentRef",ToolArgs.prop("string","完整读取获得的快照引用"),
            "baseline",ToolArgs.prop("string","快照基线"),"blockId",ToolArgs.prop("string","目标块ID"),"expectedHash",ToolArgs.prop("string","目标块hash"),
            "before",ToolArgs.prop("string","目标完整原文块"),"after",ToolArgs.prop("string","只替换授权金额后的完整块")),
            List.of("planId","documentRef","baseline","blockId","expectedHash","before","after"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){return revisions.prepare(ctx,args);}
    public CommitResult commit(ToolContext ctx,AgentActionPlan action){
        try{return revisions.commit(ctx,action);}
        catch(com.pams.common.BizException e){return CommitResult.failed(e.getMessage());}
        catch(Exception e){return new CommitResult("RESULT_UNKNOWN",0,0,"修订提交结果待核对，请查看策划书版本；不要重复执行。",Map.of(),List.of());}
    }
}
