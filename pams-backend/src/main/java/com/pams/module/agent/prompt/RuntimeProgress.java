package com.pams.module.agent.prompt;
import com.pams.module.agent.llm.LlmProvider;
import java.util.*;

/** One replaceable system fact block. Never rewrites/truncates native assistant/tool exchanges. */
public final class RuntimeProgress {
    private static final String PREFIX="【服务端动态执行预算；不是业务回执】\n";
    private RuntimeProgress(){}
    public static void refresh(List<LlmProvider.ChatMessage> messages,int max,int consumed,int toolCalls,long startedNanos){
        var data=new LinkedHashMap<String,Object>();
        data.put("toolRoundsRemaining",Math.max(0,max-consumed));data.put("toolRoundsConsumed",consumed);
        data.put("toolCallsConsumed",toolCalls);data.put("elapsedMs",Math.max(0,(System.nanoTime()-startedNanos)/1_000_000));
        data.put("totalToolCallLimit",null);data.put("totalTimeRemainingMs",null);data.put("costRemaining",null);
        data.put("unknownBudgetReason","NO_TOTAL_CALL_TIME_OR_COST_CAP_CONFIGURED");
        try{
            String text=PREFIX+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
            for(int i=0;i<messages.size();i++){var m=messages.get(i);if("system".equals(m.role())&&m.content()!=null&&m.content().startsWith(PREFIX)){messages.set(i,LlmProvider.ChatMessage.text("system",text));return;}}
            messages.add(Math.min(1,messages.size()),LlmProvider.ChatMessage.text("system",text));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
