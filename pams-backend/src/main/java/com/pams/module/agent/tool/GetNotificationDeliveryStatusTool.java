package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentNotificationActionService;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class GetNotificationDeliveryStatusTool implements AgentTool {
    private final AgentNotificationActionService actions;public GetNotificationDeliveryStatusTool(AgentNotificationActionService actions){this.actions=actions;}
    public String name(){return "getNotificationDeliveryStatus";}public String label(){return "通知投递核对";}public String runningLabel(){return "正在核对通知回执…";}
    public String description(){return "按发送回执notificationId查询本人发送的通知状态。提交受理、Outbox处理、入箱、在线信号和阅读分别统计；在线信号不证明用户看到，未入箱不能声称已送达。仅发送者在当前组织可查，不返回接收人名单。";}
    public List<String> requiredPermissions(){return List.of("notification:send");}public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("notificationId",ToolArgs.prop("integer","真实发送回执的通知ID")),List.of("notificationId"));}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){return actions.status(ctx,ToolArgs.lng(args,"notificationId"));}
}
