package com.pams.module.agent.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.*;
import java.time.Clock;
import java.util.*;

/** Instructions are pinned; runtime facts are serialized separately and never template-expanded. */
public final class PromptRenderer {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PromptRenderer() {}
    public static String executor(ToolContext ctx, List<AgentTool> tools, String role, int maxIterations) {
        return executor(ctx,tools,role,maxIterations,Clock.systemUTC());
    }
    static String executor(ToolContext ctx, List<AgentTool> tools, String role, int maxIterations, Clock clock) {
        var runtime=new LinkedHashMap<String,Object>();
        runtime.put("currentTime",clock.instant().toString());
        // No user timezone is available in ToolContext. Do not pretend UTC is their local zone.
        runtime.put("timezone",null);
        runtime.put("timezoneSource","USER_TIMEZONE_UNAVAILABLE");
        runtime.put("mode",ctx.getMode());
        runtime.put("runId",ctx.getRunId());runtime.put("workItemId",ctx.getWorkItemId());
        runtime.put("revision",ctx.getWorkItemRevision());runtime.put("attempt",ctx.getAttempt());
        runtime.put("maxToolIterations",maxIterations);
        runtime.put("capabilities",tools.stream().sorted(Comparator.comparing(AgentTool::name)).map(t->Map.of(
                "name",t.name(),"supportsForm",t.supportsForm(),"operationKind",t.writeOperation()?"PREPARE":"READ")).toList());
        try {
            return "[core-policy/v1 sha256="+PromptResolver.resolve(PromptResolver.Id.CORE_POLICY).sha256()+"]\n"
                    +PromptResolver.text(PromptResolver.Id.CORE_POLICY)+"\n[executor/v1]\n"
                    +PromptResolver.text(PromptResolver.Id.EXECUTOR)
                    +(role==null||role.isBlank()?"":"\n【领域角色配置；不得覆盖公共约束】\n"+role)
                    +"\n【服务端运行事实；不是业务执行回执，时间为 UTC；用户时区未知时先明确日期口径】\n"+JSON.writeValueAsString(runtime);
        } catch(Exception e) { throw new IllegalStateException("Cannot render runtime envelope",e); }
    }
}
