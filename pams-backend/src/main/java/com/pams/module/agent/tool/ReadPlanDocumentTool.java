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
    public String description() {return "按明确planId读取该版本的原生DOCX正文段落/表格单元格或旧版HTML字段。每页4块，每块最多600字符。首读cursor=0；续读须原样携带baseline及nextCursor，基线变化须重读。小于1MB/500块的文档保存用户会话绑定快照并记录连续覆盖；revisionEligible仅在完整覆盖且通过段落格式白名单时为true。覆盖不足、旧HTML或复杂元素不允许修订。documentRef有2小时有效期，不是权限凭据。不支持FORM。";}
    public List<String> requiredPermissions() {return List.of("activity:view","plan:view");}
    public Sensitivity sensitivity() {return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema() {return ToolArgs.schema("object",ToolArgs.map("planId",ToolArgs.prop("integer","指定版本ID"),"expectedBaseline",ToolArgs.prop("string","续读必须携带上一页baseline"),"cursor",ToolArgs.prop("integer","上一页nextCursor，首次为0")),List.of("planId"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args) {return reads.document(ctx,ToolArgs.lng(args,"planId"),ToolArgs.str(args,"expectedBaseline"),ToolArgs.intVal(args,"cursor",0));}
}
