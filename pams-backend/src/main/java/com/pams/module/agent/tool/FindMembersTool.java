package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentDirectoryReadService;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class FindMembersTool implements AgentTool {
    private final AgentDirectoryReadService reads;public FindMembersTool(AgentDirectoryReadService reads){this.reads=reads;}
    public String name(){return "findMembers";}public String label(){return "成员定位";}public String runningLabel(){return "正在定位成员…";}
    public String description(){return "按姓名关键词、部门/届别有界定位成员，每页5条，page从0开始。返回memberId与唯一关联的userId（学号严格匹配）及实际accountDeptId，不含学号、电话等明细。任务指派用userId+accountDeptId，不能把memberId或历史成员部门当作账号身份。重名/多账号须消歧，不默认首项；成员不等于可接收通知的账号。";}
    public List<String> requiredPermissions(){return List.of("member:view");}public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("keyword",ToolArgs.prop("string","姓名关键词"),"deptId",ToolArgs.prop("integer","部门ID"),"sessionId",ToolArgs.prop("integer","届别ID"),"page",ToolArgs.prop("integer","页码，从0开始")),List.of());}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){return reads.members(ctx,args);}
}
