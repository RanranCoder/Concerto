package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.service.AgentJson;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.*;

/** Enforced at dispatch as well as model visibility; natural language cannot broaden node authority. */
@Component
public class ControlledToolGuard {
    private final AgentWorkItemRepository nodes;private final AgentStepRepository steps;private final ObjectMapper json=new ObjectMapper();
    public ControlledToolGuard(AgentWorkItemRepository nodes,AgentStepRepository steps){this.nodes=nodes;this.steps=steps;}
    public boolean allows(AgentTool tool,ToolContext ctx){
        if(ctx.getWorkItemId()==null)return true;
        var node=nodes.findById(ctx.getWorkItemId()).orElse(null);
        if(node==null || !Objects.equals(node.getRunId(),ctx.getRunId()) || !Objects.equals(node.getRevision(),ctx.getWorkItemRevision()))return false;
        if(node.getContractJson()==null)return true;
        var c=ControlledBindings.tree(node.getContractJson());
        return tool.name().equals(c.path("capability").asText()) && tool.writeOperation()==c.path("write").asBoolean()
                && json.valueToTree(tool.parametersSchema()).equals(c.path("schema"));
    }
    public void arguments(AgentTool tool,ToolContext ctx,Map<String,Object> args){
        if(ctx.getWorkItemId()==null)return;var node=nodes.findById(ctx.getWorkItemId()).orElseThrow();
        if(node.getContractJson()==null)return;
        if(!allows(tool,ctx) || !"RUNNING".equals(node.getStatus()) || !Objects.equals(ctx.getAttempt(),node.getAttempt()))throw new AccessDeniedException("受控节点能力或执行版本无效");
        var step=steps.findByWorkItemIdAndRevisionAndAttempt(node.getId(),node.getRevision(),node.getAttempt()).orElseThrow();
        var resolved=ControlledBindings.tree(step.getInputJson()).get("resolvedArguments");
        if(resolved==null || !resolved.equals(json.valueToTree(args)))throw new AccessDeniedException("参数不符合服务端已解析的输入绑定");
    }
}
