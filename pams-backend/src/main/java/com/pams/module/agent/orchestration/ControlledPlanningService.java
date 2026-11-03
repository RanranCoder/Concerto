package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.*;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.*;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.prompt.PromptResolver;
import com.pams.module.agent.service.*;
import com.pams.module.agent.registry.AgentRegistry;
import org.springframework.stereotype.Service;
import java.util.*;

/** Opt-in, preview-only model planner. Existing rule execution never consumes this response. */
@Service
public class ControlledPlanningService {
    private static final Set<String> PREPARATIONS=Set.of("createActivity","draftPlanSections","draftArticleFromActivity","generateActivityTasks","prepareTaskPlanChange","prepareTaskProgressUpdate","preparePlanRevision","prepareArticleRevision","prepareArticleSubmission","preparePlanSubmission");
    private final AgentProperties props;private final AgentRegistry agents;private final ToolRegistry tools;
    private final AgentLlmCaller caller;private final CoordinatorAgent coordinator;private final ObjectMapper json=new ObjectMapper();
    public ControlledPlanningService(AgentProperties props,AgentRegistry agents,ToolRegistry tools,AgentLlmCaller caller,CoordinatorAgent coordinator){this.props=props;this.agents=agents;this.tools=tools;this.caller=caller;this.coordinator=coordinator;}
    public record Preview(String status,boolean previewOnly,String reasonCode,int attempts,String promptVersion,String promptSha256,
                          String capabilitySha256,String model,Blackboard plan,List<String> constraints,List<String> clarifications,List<ControlledPlanValidator.Step> steps){}
    public Preview preview(String goal,ToolContext ctx){
        return previewForEvaluation(goal,ctx,null);
    }
    Preview previewForEvaluation(String goal,ToolContext ctx,String evaluationStream){
        if(ctx==null || ctx.getLoginUser()==null || ctx.getUserId()==null || !Objects.equals(OrgContext.get(),ctx.getLoginUser().getOrgId()))throw new BizException(403,"规划请求缺少有效身份或组织上下文");
        if(goal==null || goal.isBlank() || goal.length()>4000)throw new BizException(400,"规划目标需为1–4000字");goal=goal.strip();
        if(ctx.isKb())throw new BizException(409,"知识问答模式不进入业务规划，请使用独立知识问答链路");
        var baseline=coordinator.plan(goal);
        if(!props.isControlledPlanningEnabled() || !props.getControlledPlanningOrgIds().contains(ctx.getLoginUser().getOrgId()))return fallback(baseline,"ROLLOUT_DISABLED",0,"","");
        Map<String,ControlledPlanValidator.Capability> initial=capabilities(ctx);
        if(initial.isEmpty())return fallback(baseline,"NO_AVAILABLE_CAPABILITIES",0,"","");
        if(initial.size()>120)return fallback(baseline,"CAPABILITY_BUDGET",0,"","");
        var menu=new ArrayList<Map<String,Object>>();
        for(var cap:initial.values()){
            menu.add(Map.of("agent",cap.agent(),"capability",cap.tool().name()));
        }
        String input;
        var schemas=new TreeMap<String,Object>();for(var cap:initial.values())schemas.put(cap.tool().name(),compactSchema(cap.tool().parametersSchema()));
        var descriptions=new TreeMap<String,Object>();for(var cap:initial.values())descriptions.put(cap.tool().name(),Map.of("description",clip(cap.tool().description(),60),"operationKind",cap.tool().writeOperation()?"PREPARE":"READ"));
        try{input=json.writeValueAsString(Map.of("goal",goal,"mode",ctx.getMode(),"capabilities",menu,"parameterSchemas",schemas,"capabilityDescriptions",descriptions,"ruleBaseline",baseline.getPlan()));}
        catch(Exception e){return fallback(baseline,"INPUT_INVALID",0,"","");}
        if(input.length()>48000)return fallback(baseline,"INPUT_BUDGET",0,"","");
        String digest;try{digest=PlanDocumentReader.hash(json.writeValueAsString(Map.of("capabilities",menu,"parameterSchemas",schemas,"descriptions",descriptions)));}catch(Exception e){return fallback(baseline,"INPUT_INVALID",0,"","");}
        String stream=evaluationStream==null?"plan-preview-"+UUID.randomUUID():evaluationStream;int attempts=0;String model="",failure="INVALID_PLAN";
        var messages=new ArrayList<LlmProvider.ChatMessage>();messages.add(LlmProvider.ChatMessage.text("system",PromptResolver.text(PromptResolver.Id.PLANNER)));messages.add(LlmProvider.ChatMessage.text("user",input));
        try{
            caller.beginTask(stream,ctx);
            for(int round=0;round<2;round++){
                attempts++;
                var response=caller.callPlanning(messages,stream);
                if(response==null || response.degraded() || "stub".equals(response.provider()) || "length".equals(response.finishReason()) || response.toolCalls()!=null&&!response.toolCalls().isEmpty())return fallback(baseline,"MODEL_UNVERIFIED",attempts,digest,model);
                model=clip(response.model(),200);
                if(response.content()!=null && response.content().length()>24000)return fallback(baseline,"OUTPUT_BUDGET",attempts,digest,model);
                try{
                    // Revocation takes effect while a response is in flight; new grants cannot widen this preview.
                    var current=capabilities(ctx);current.keySet().retainAll(initial.keySet());
                    if(!current.keySet().equals(initial.keySet()))return fallback(baseline,"CAPABILITIES_REVOKED",attempts,digest,model);
                    var result=ControlledPlanValidator.validate(response.content(),goal,current);
                    return new Preview(result.clarifications().isEmpty()?"MODEL_PLAN_VALIDATED":"NEEDS_CLARIFICATION",true,"",attempts,"planner/v1",PromptResolver.resolve(PromptResolver.Id.PLANNER).sha256(),digest,model,
                            result.plan(),result.constraints(),result.clarifications(),result.steps());
                }catch(java.util.concurrent.CancellationException e){throw e;}
                catch(Exception invalid){
                    if(round==0){messages.add(LlmProvider.ChatMessage.assistantCalling(response));messages.add(LlmProvider.ChatMessage.text("user","服务端校验未通过。仅修复JSON契约、当前可用能力、引用和依赖；不得扩大原始目标。这是唯一一次修复机会。"));}
                }
            }
        }catch(java.util.concurrent.CancellationException | AgentEvaluationMeter.LimitExceeded e){throw e;}
        catch(IllegalArgumentException e){failure=Objects.toString(e.getMessage(),"").startsWith("Planning context")?"CONTEXT_BUDGET":"MODEL_UNAVAILABLE";}
        catch(Exception e){failure=switch(Objects.toString(e.getMessage(),"")){
            case "Responses 响应未完成"->"MODEL_OUTPUT_INCOMPLETE";
            case "模型调用超时"->"MODEL_TIMEOUT";
            case "模型连接或协议响应失败"->"MODEL_TRANSPORT_ERROR";
            default->"MODEL_UNAVAILABLE";};}
        finally{caller.endTask(stream);}
        return fallback(baseline,failure,attempts,digest,model);
    }
    public Map<String,ControlledPlanValidator.Capability> capabilities(ToolContext ctx){
        var result=new TreeMap<String,ControlledPlanValidator.Capability>();var visible=tools.visibleTools(ctx);
        for(var agent:agents.enabled()){
            var scope=agents.toolScopeOf(agent);
            for(var tool:visible)if((scope==null || scope.contains(tool.name())) && (!tool.writeOperation() || !ctx.isReadonly() && PREPARATIONS.contains(tool.name())))
                result.put(ControlledPlanValidator.key(agent.getName(),tool.name()),new ControlledPlanValidator.Capability(agent.getName(),tool));
        }return result;
    }
    private Preview fallback(Blackboard baseline,String reason,int attempts,String digest,String model){return new Preview("RULE_BASELINE",true,reason,attempts,"planner/v1",PromptResolver.resolve(PromptResolver.Id.PLANNER).sha256(),digest,model,baseline,List.of(),List.of(),List.of());}
    private static String clip(String value,int max){return value==null?"":value.substring(0,Math.min(value.length(),max));}
    private static Object compactSchema(Object value){
        if(value instanceof Map<?,?> map){var out=new TreeMap<String,Object>();for(var entry:map.entrySet()){
            String key=entry.getKey().toString();if(Set.of("description","title","examples","default").contains(key)&&!(entry.getValue() instanceof Map<?,?>))continue;
            out.put(key,compactSchema(entry.getValue()));}return out;}
        if(value instanceof List<?> list)return list.stream().map(ControlledPlanningService::compactSchema).toList();return value;
    }
}
