package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentNotificationActionService;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class ResolveNotificationAudienceTool implements AgentTool {
    private final AgentNotificationActionService actions;public ResolveNotificationAudienceTool(AgentNotificationActionService actions){this.actions=actions;}
    public String name(){return "resolveNotificationAudience";}public String label(){return "通知受众解析";}public String runningLabel(){return "正在校验通知受众…";}
    public String description(){return "按明确userIds、deptIds、roleCodes中的非空条件解析当前组织通知受众。须明确combination=UNION或INTERSECTION；同组内为并集，不默认全员。最多200人，返回15分钟快照句柄与数量，不披露名单；发送权限不附带成员查询权限。确认时集合变化须重新预览。";}
    public List<String> requiredPermissions(){return List.of("notification:send");}public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("userIds",Map.of("type","array","items",Map.of("type","integer")),"deptIds",Map.of("type","array","items",Map.of("type","integer")),"roleCodes",Map.of("type","array","items",Map.of("type","string")),"combination",Map.of("type","string","enum",List.of("UNION","INTERSECTION"))),List.of("combination"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){return actions.resolve(ctx,args);}
}
