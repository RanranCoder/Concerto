package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.*;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.orchestration.WorkItemState;
import org.springframework.stereotype.Service;
import java.util.*;

/** Reviews the actual preview before a confirmation token exists. Review has no tools or write capability. */
@Service
public class PlanReviewService {
    private final WorkItemState state;
    private final AgentLlmCaller caller;
    private final PolicyGuard policy;
    private final ObjectMapper json=new ObjectMapper();
    public PlanReviewService(WorkItemState state,AgentLlmCaller caller,PolicyGuard policy) {
        this.state=state;this.caller=caller;this.policy=policy;
    }
    public String review(ToolContext ctx,AgentTool tool,String preview) {
        if(ctx.getWorkItemId()==null || preview==null || preview.isBlank()) return "NOT_REVIEWED";
        var result=evaluate(ctx,tool,preview);state.review(ctx,result);return result.status();
    }
    public WorkItemState.Review evaluate(ToolContext ctx,AgentTool tool,String preview) {
        if(ctx.getWorkItemId()==null) return new WorkItemState.Review("UNVERIFIED",List.of());
        String text=policy.egressText(tool.sensitivity(),preview);
        WorkItemState.Review result=new WorkItemState.Review("UNVERIFIED",List.of());
        // Never persist sensitive excerpts in the review ledger.
        if(text!=null && tool.sensitivity()==Sensitivity.NORMAL && text.length()<=24000) {
            var node=state.nodes(ctx.getRunId()).stream().filter(n->n.getId().equals(ctx.getWorkItemId())).findFirst().orElseThrow();
            String instruction=node.getInstruction();
            int roleBoundary=instruction==null?-1:instruction.lastIndexOf("\n本步骤：");
            if(instruction!=null && instruction.startsWith("用户目标：") && roleBoundary>0)instruction=instruction.substring(0,roleBoundary);
            String sources=state.nodes(ctx.getRunId()).stream().filter(n->state.dependencies(node).contains(n.getNodeKey()))
                .map(n->Map.entry(n,state.sourceFacts(n))).filter(e->!e.getValue().isBlank()).map(e->"step:"+e.getKey().getNodeKey()+" "+e.getValue()).reduce("",(a,b)->a+"\n"+b);
            if(sources.length()<=12000) {
                try {
                    var response=caller.callIsolated(List.of(
                        LlmProvider.ChatMessage.text("system", com.pams.module.agent.prompt.PromptResolver.text(com.pams.module.agent.prompt.PromptResolver.Id.REVIEWER)),
                        LlmProvider.ChatMessage.text("user","【任务 task】\n"+instruction+"\n【上游】\n"+sources+"\n【正文】\n"+text)),List.of(),ctx.getStreamId());
                    if(!response.degraded() && !"stub".equals(response.provider())) result=parse(response.content(),text,instruction,sources);
                } catch(java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                catch(Exception ignored) { /* Invalid/unavailable review stays explicitly unverified. */ }
            }
        }
        return result;
    }
    WorkItemState.Review parse(String content,String preview,String instruction,String sources) throws Exception {
        var root=json.readTree(content);
        if(!root.isObject() || !root.path("issues").isArray() || root.path("issues").size()>20) throw new IllegalArgumentException("审查格式无效");
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var item:root.path("issues")) {
            String severity=item.path("severity").asText(),quote=item.path("quote").asText(),reason=item.path("reason").asText(),source=item.path("sourceRef").asText();
            if(!Set.of("BLOCKING","WARNING").contains(severity) || quote.isBlank() || quote.length()>1000 || reason.isBlank() || reason.length()>2000
                || !preview.contains(quote) || !("task".equals(source) || source.matches("step:[1-9][0-9]*") && sources.lines().anyMatch(line->line.startsWith(source+" ")))) throw new IllegalArgumentException("审查依据或位置无效");
            int offset=preview.indexOf(quote),line=1;
            for(int i=0;i<offset;i++) if(preview.charAt(i)=='\n') line++;
            rows.add(Map.of("severity",severity,"code","CONTENT","location","正文第 "+line+" 行，字符 "+(offset+1),"quote",quote,"reason",reason,"sourceRef",source));
        }
        return new WorkItemState.Review(rows.stream().anyMatch(r->"BLOCKING".equals(r.get("severity")))?"BLOCKED":"CHECKED",rows);
    }
}
