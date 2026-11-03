package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentPlanReadService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ListPlanVersionsTool implements AgentTool {
    private final AgentPlanReadService reads;
    public ListPlanVersionsTool(AgentPlanReadService reads) {this.reads=reads;}
    public String name() {return "listPlanVersions";}
    public String label() {return "策划书版本";}
    public String runningLabel() {return "正在查询策划书版本…";}
    public String description() {return "列出明确活动下有权读取的策划书版本，每页5条，page从0开始。按版本倒序，最新可见不代表最新已通过。用planId调用readPlanDocument获取原文；列表不是正文。不支持FORM或写入。";}
    public List<String> requiredPermissions() {return List.of("activity:view","plan:view");}
    public Sensitivity sensitivity() {return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema() {return ToolArgs.schema("object",ToolArgs.map("activityId",ToolArgs.prop("integer","明确的活动ID"),"page",ToolArgs.prop("integer","页码，从0开始")),List.of("activityId"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args) {return reads.versions(ctx,ToolArgs.lng(args,"activityId"),ToolArgs.intVal(args,"page",0));}
}
