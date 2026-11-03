package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentDirectoryReadService;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class ListOrgUnitsTool implements AgentTool {
    private final AgentDirectoryReadService reads;public ListOrgUnitsTool(AgentDirectoryReadService reads){this.reads=reads;}
    public String name(){return "listOrgUnits";}public String label(){return "组织定位";}public String runningLabel(){return "正在查询组织标识…";}
    public String description(){return "分页读取当前组织部门ID/名称、成员岗位代码positions，以及通知可选择的角色标签audienceRoles，各5条，page从0开始。成员岗位代码不是账号角色ID，未提供岗位ID时不伪造；不导出权限配置、账户或联系方式。";}
    public List<String> requiredPermissions(){return List.of("member:view");}public Sensitivity sensitivity(){return Sensitivity.NORMAL;}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("page",ToolArgs.prop("integer","页码，从0开始")),List.of());}
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){return reads.units(ctx,args);}
}
