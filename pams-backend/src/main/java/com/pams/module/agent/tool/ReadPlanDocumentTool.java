package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentPlanReadService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ReadPlanDocumentTool implements AgentTool {
    private final AgentPlanReadService reads;
    public ReadPlanDocumentTool(AgentPlanReadService reads) {this.reads=reads;}
    public String name() {return "readPlanDocument";}
    public String label() {return "策划书原文";}
    public String runningLabel() {return "正在读取指定版本原文…";}
    public String description() {return "按 planId 读取原生 DOCX 正文段落、表格单元格或旧版 HTML 字段。每页 4 块，每块最多 600 字，续读使用 baseline 和 nextCursor。1MB/500块以内会生成绑定会话的 documentRef。preparePlanRevision 的 edits 使用已读取的普通 Word 块，旧预算模式还要求 revisionEligible=true。旧 HTML 不支持原生 Word 修订。";}
    public List<String> requiredPermissions() {return List.of("activity:view","plan:view");}
    public Sensitivity sensitivity() {return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema() {return ToolArgs.schema("object",ToolArgs.map("planId",ToolArgs.prop("integer","指定版本ID"),"expectedBaseline",ToolArgs.prop("string","续读必须携带上一页baseline"),"cursor",ToolArgs.prop("integer","上一页nextCursor，首次为0")),List.of("planId"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args) {return reads.document(ctx,ToolArgs.lng(args,"planId"),ToolArgs.str(args,"expectedBaseline"),ToolArgs.intVal(args,"cursor",0));}
}
