package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.ConversationActions;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class QueryConversationActionsTool implements AgentTool {
    private final ConversationActions actions;
    public QueryConversationActionsTool(ConversationActions actions){this.actions=actions;}
    public String name(){return "queryConversationActions";}
    public String label(){return "查看当前待处理事项";}
    public String runningLabel(){return "正在接续当前任务…";}
    public String description(){return "查询当前会话当前用户的待补充、待确认和待修订任务。用户补充上轮信息时先查询，使用真实taskId及fields调用supplyTaskInput，不重新创建相同任务；不返回确认令牌。";}
    public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",Map.of(),List.of());}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){return actions.list(ctx);}
}
