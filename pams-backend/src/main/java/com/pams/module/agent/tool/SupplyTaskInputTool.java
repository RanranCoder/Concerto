package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.ConversationActions;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class SupplyTaskInputTool implements PersistedInteractionTool {
    private final ConversationActions actions;
    public SupplyTaskInputTool(ConversationActions actions){this.actions=actions;}
    public String name(){return "supplyTaskInput";}
    public String label(){return "接续任务信息";}
    public String runningLabel(){return "正在应用你补充的信息…";}
    public String description(){return "把用户在自然对话中补充的信息传回同一待填写任务。先queryConversationActions获取真实taskId和fields；values只含已明确提供或经授权查询核实的值。不要猜测日期、身份和范围。只更新输入与预览，不确认、不提交、不发布。";}
    public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public boolean writeOperation(){return true;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("taskId",ToolArgs.prop("integer","当前会话待填写任务ID"),"values",ToolArgs.prop("object","字段名到补充值的映射，仅使用任务fields中的字段")),List.of("taskId","values"));}
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){
        if(!(args.get("values") instanceof Map<?,?> values))return ToolResult.failed("values必须是字段对象");
        Long id=ToolArgs.lng(args,"taskId");if(id==null)return ToolResult.failed("缺少taskId");
        return actions.supply(ctx,id,(Map<String,Object>)values);
    }
}
