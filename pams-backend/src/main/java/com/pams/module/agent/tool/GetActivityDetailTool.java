package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentPlanReadService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class GetActivityDetailTool implements AgentTool {
    private final AgentPlanReadService reads;
    public GetActivityDetailTool(AgentPlanReadService reads) {this.reads=reads;}
    public String name() {return "getActivityDetail";}
    public String label() {return "活动详情";}
    public String runningLabel() {return "正在读取活动详情…";}
    public String description() {return "读取明确 activityId 的活动基本事实；不含成员和敏感子模块。先通过活动查询消歧，不猜 ID；无 FORM，不写入。活动尚无可靠 revision，不可将 updatedAt 当并发令牌。";}
    public List<String> requiredPermissions() {return List.of("activity:view");}
    public Sensitivity sensitivity() {return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema() {return ToolArgs.schema("object",ToolArgs.map("activityId",ToolArgs.prop("integer","明确的活动ID")),List.of("activityId"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args) {return reads.detail(ctx,ToolArgs.lng(args,"activityId"));}
}
