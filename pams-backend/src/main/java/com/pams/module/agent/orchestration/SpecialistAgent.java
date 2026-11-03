package com.pams.module.agent.orchestration;

import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.service.*;
import com.pams.module.agent.stream.*;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

/** Bounded specialist loop. Writes prepare actions through the same gate as the ordinary loop. */
@Service
public class SpecialistAgent {
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.agent.service.ContextAssembler contextAssembler;
    public record ToolInvocation(String name,String label,String status,String summary,long durationMs) {}
    public record Result(String agent,String text,List<ToolInvocation> invocations,String state,
                         String provider,boolean degraded,Sensitivity sensitivity,
                         AgentActionPlan plan,Map<String,Object> form,
                         List<Map<String,Object>> references,List<Map<String,Object>> toolCalls) {
        public Result(String agent,String text,List<ToolInvocation> invocations) {
            this(agent,text,invocations,invocations.stream().anyMatch(i->!"OK".equals(i.status())) ? "FAILED" : "SUCCESS",
                "",false,Sensitivity.NORMAL,null,null,List.of(),List.of());
        }
        public boolean waiting() { return plan != null || form != null; }
    }
    private final AgentRegistry registry;
    private final ToolRegistry toolRegistry;
    private final AgentLlmCaller caller;
    private final AgentEventPublisher publisher;
    private final ToolOutcomeHandler outcomes;
    private final PolicyGuard policy;
    private final AuditService audit;
    private final com.pams.module.agent.config.AgentProperties props;
    public SpecialistAgent(AgentRegistry registry,ToolRegistry toolRegistry,AgentLlmCaller caller,
                           AgentEventPublisher publisher,ToolOutcomeHandler outcomes,PolicyGuard policy,
                           AuditService audit,com.pams.module.agent.config.AgentProperties props) {
        this.registry=registry; this.toolRegistry=toolRegistry; this.caller=caller; this.publisher=publisher;
        this.outcomes=outcomes; this.policy=policy; this.audit=audit; this.props=props;
    }
    public Result execute(String agent,String task,ToolContext ctx,List<LlmProvider.ToolCallRequest> predecided,
                          String streamId,String username) {
        List<AgentTool> scoped=scopedTools(agent,ctx);
        Set<String> allowed=new HashSet<>(scoped.stream().map(AgentTool::name).toList());
        List<LlmProvider.ToolSpec> specs=scoped.stream().map(t->new LlmProvider.ToolSpec(t.name(),t.description(),t.parametersSchema())).toList();
        List<LlmProvider.ChatMessage> messages=new ArrayList<>();
        String system = com.pams.module.agent.prompt.PromptRenderer.executor(ctx, scoped, rolePrompt(agent), props.getMaxToolIterations());
        if (contextAssembler != null) messages.addAll(contextAssembler.specialist(system, ctx.getConversationId(), task==null?"":task, ctx));
        else {
            messages.add(LlmProvider.ChatMessage.text("system",system));
            messages.add(LlmProvider.ChatMessage.text("user",task==null?"":task));
        }
        List<ToolInvocation> invocations=new ArrayList<>();
        List<Map<String,Object>> refs=new ArrayList<>(), rows=new ArrayList<>();
        String provider="", text="", state="SUCCESS";
        boolean degraded=false;
        Sensitivity sensitivity=Sensitivity.NORMAL;
        boolean actionRequired=!ctx.isReadonly() && !ctx.isKb() && scoped.stream().anyMatch(AgentTool::writeOperation)
            && task!=null && List.of("待确认","等待确认","拟执行清单","保存草稿","生成推文草稿").stream().anyMatch(task::contains);
        int max=Math.max(1,props.getMaxToolIterations());
        for(int round=0;round<=max;round++) {
            if(aborted(streamId)) { state="CANCELLED"; break; }
            LlmProvider.ChatResponse response=null;
            List<LlmProvider.ToolCallRequest> calls=predecided;
            if(calls==null) {
                try { response=caller.callIsolated(messages,specs,streamId); }
                catch(java.util.concurrent.CancellationException e) { state="CANCELLED"; break; }
                provider=response.provider(); degraded|=response.degraded(); text=response.content()==null?"":response.content();
                calls=response.toolCalls();
            }
            if(calls==null || calls.isEmpty()) {
                if(actionRequired && predecided==null) {
                    if(round<max && !degraded) {
                        messages.add(response == null ? LlmProvider.ChatMessage.text("assistant", text) : LlmProvider.ChatMessage.assistantCalling(response));
                        messages.add(LlmProvider.ChatMessage.text("user","本任务要求可确认预览，目前没有对应清单。请使用可见工具准备待确认清单；缺必要参数时明确说明。没有工具回执不得宣称已保存、已发布或已完成。"));
                        continue;
                    }
                    if(!"FAILED".equals(state))state="PARTIAL";
                    text="尚未生成可确认清单。"+text;break;
                }
                if(response!=null && "length".equals(response.finishReason()) && !"FAILED".equals(state))state="PARTIAL";
                if(!"FAILED".equals(state) && (text.isBlank() || (invocations.isEmpty() && degraded))) state="PARTIAL";
                break;
            }
            if(round==max) { if(!"FAILED".equals(state)) state="PARTIAL"; text="子任务已达到工具调用上限，尚未完成。"; break; }
            if(response!=null) messages.add(LlmProvider.ChatMessage.assistantCalling(response));
            for(LlmProvider.ToolCallRequest call:calls) {
                if(aborted(streamId)) { state="CANCELLED"; break; }
                AgentTool tool=toolRegistry.find(call.name()).orElse(null);
                if(tool==null || !allowed.contains(call.name())) {
                    invocations.add(new ToolInvocation(call.name(),call.name(),"DENIED","工具不可用或超出专家作用域",0));
                    messages.add(LlmProvider.ChatMessage.toolResult(call.id(),"工具不可用或超出专家作用域")); state="FAILED"; continue;
                }
                emit(ctx,streamId,AgentStreamEvent.toolStart(streamId,publisher.nextSeq(streamId),tool.name(),tool.runningLabel()));
                long start=System.currentTimeMillis();
                ToolResult result;
                try { result=toolRegistry.dispatch(call.name(),ctx,call.arguments()); }
                catch(AccessDeniedException e) { result=ToolResult.denied(e.getMessage()); }
                catch(Exception e) { result=ToolResult.failed(e.getMessage()==null?"工具执行异常":e.getMessage()); }
                long duration=System.currentTimeMillis()-start;
                sensitivity=Sensitivity.max(sensitivity,tool.sensitivity());
                String safe=policy.egressText(tool.sensitivity(),tool.toLlmText(ctx,result));
                if(safe==null) safe="此结果为受限资料，请在对应业务页面查看。";
                invocations.add(new ToolInvocation(tool.name(),tool.label(),result.status(),safe,duration));
                refs.addAll(result.references()==null?List.of():result.references());
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("tool",tool.name()); row.put("label",tool.label()); row.put("status",result.status());
                row.put("durationMs",duration); row.put("write",tool.writeOperation()); row.put("sensitivity",tool.sensitivity().name().toLowerCase());
                row.put("argsJson",policy.egressArgs(tool.sensitivity(),audit.toJson(call.arguments()))); row.put("ts",start);
                rows.add(row);
                publisher.recordToolCall(ctx.getConversationId(),null,streamId,publisher.nextSeq(streamId),ctx.getUserId(),tool.name(),tool.label(),
                    audit.toJson(call.arguments()),tool.sensitivity().name().toLowerCase(),tool.writeOperation()?"WRITE":"READ",duration,result.status(),result.error());
                try { audit.log(ctx,tool.name(),call.arguments(),tool.writeOperation()?"PREVIEW":"READ",tool.sensitivity(),result.status()); }
                catch(Exception ignored) { /* audit is independent of a successful preview */ }
                emit(ctx,streamId,AgentStreamEvent.toolEnd(streamId,publisher.nextSeq(streamId),tool.name(),tool.label(),result.status(),duration,result.error()));
                if(aborted(streamId)) { state="CANCELLED"; break; }
                ToolOutcomeHandler.PendingAction pending=outcomes.prepare(ctx,tool,result,call);
                if(pending.waiting()) return new Result(agent,safe,invocations,pending.form()!=null?"WAITING_INPUT":"WAITING_CONFIRM",
                    provider,degraded,sensitivity,pending.plan(),pending.form(),refs,rows);
                messages.add(LlmProvider.ChatMessage.toolResult(call.id(),safe));
                if(!result.isOk()) state="FAILED";
            }
            if("CANCELLED".equals(state) || predecided!=null) break;
        }
        return new Result(agent,policy.egressText(sensitivity,text),invocations,state,provider,degraded,sensitivity,null,null,refs,rows);
    }
    public List<AgentTool> scopedTools(String agent,ToolContext ctx) {
        Optional<AgentDefinition> definition=registry.byName(agent);
        if(definition.isEmpty()) return List.of();
        List<String> scope=registry.toolScopeOf(definition.get());
        List<AgentTool> visible=toolRegistry.visibleTools(ctx);
        return scope==null?visible:visible.stream().filter(t->scope.contains(t.name())).toList();
    }
    private boolean aborted(String stream) { return stream!=null && publisher.isAborted(stream); }
    private String rolePrompt(String agent) { return registry.byName(agent).map(AgentDefinition::getRolePrompt).filter(Objects::nonNull).orElse("你是组织工作助手。"); }
    private void emit(ToolContext ctx,String stream,AgentStreamEvent event) {
        if(stream!=null && ctx.getLoginUser()!=null) publisher.publish(ctx.getLoginUser().getUsername(),event.withContext(ctx));
    }
}
