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
    public String label(){return "Word 定向修订";}
    public String runningLabel(){return "正在校验 Word 修订与原文差异…";}
    public String description(){return "先用 readPlanDocument 读取当前 Word 及目标块，传 planId、documentRef、baseline。edits 支持段落和单段落单元格内的精确替换，可跨文本格式。每项提供 blockId、expectedHash、before、after。跨正文段落时 followingBlocks 按顺序列出其余段落，before 用换行连接。插入或删除须引用完整正文段落。用户委托保存时设置 saveDraft=true。回执的 wordPageReview 给出版面复检状态。";}
    public List<String> requiredPermissions(){return List.of("activity:view","plan:view","plan:edit");}
    public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    @Override public boolean saveDraftInTurn(Map<String,Object> arguments) {
        return arguments.get("edits") instanceof List<?> && Boolean.TRUE.equals(arguments.get("saveDraft"));
    }
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map(
            "planId",ToolArgs.prop("integer","指定当前版本ID"),"documentRef",ToolArgs.prop("string","完整读取获得的快照引用"),
            "baseline",ToolArgs.prop("string","快照基线"),"blockId",ToolArgs.prop("string","目标块ID"),"expectedHash",ToolArgs.prop("string","目标块hash"),
            "saveDraft",ToolArgs.prop("boolean","用户委托保存修改时设为 true，另存草稿版本"),
            "before",ToolArgs.prop("string","旧预算模式的目标完整原文块"),"after",ToolArgs.prop("string","旧预算模式的完整新值"),
            "edits", Map.of("type","array","items",ToolArgs.schema("object",ToolArgs.map(
                    "blockId",ToolArgs.prop("string","已读取的文档块 ID"),
                    "expectedHash",ToolArgs.prop("string","原文块指纹"),
                    "before",ToolArgs.prop("string","块内唯一原文。跨段落用换行连接。插入、删除须传入完整锚点原文"),
                    "after",ToolArgs.prop("string","替换或插入的文本。正文中的换行创建段落。删除时传空字符串"),
                    "operation",Map.of("type","string","enum",List.of("REPLACE","INSERT_BEFORE","INSERT_AFTER","DELETE"),
                            "description","缺省为 REPLACE"),
                    "followingBlocks",Map.of("type","array","items",ToolArgs.schema("object",ToolArgs.map(
                            "blockId",ToolArgs.prop("string","连续的下一正文段落 ID"),
                            "expectedHash",ToolArgs.prop("string","下一段原文指纹")),List.of("blockId","expectedHash")))),
                    List.of("blockId","expectedHash","before","after")))),
            List.of("planId","documentRef","baseline"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){return revisions.prepare(ctx,args);}
    public CommitResult commit(ToolContext ctx,AgentActionPlan action){
        try{return revisions.commit(ctx,action);}
        catch(com.pams.common.BizException e){return CommitResult.failed(e.getMessage());}
        catch(Exception e){return new CommitResult("RESULT_UNKNOWN",0,0,"修订提交结果待核对，请查看策划书版本；不要重复执行。",Map.of(),List.of());}
    }
}
