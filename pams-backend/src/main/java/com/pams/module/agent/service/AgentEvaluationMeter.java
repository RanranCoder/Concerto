package com.pams.module.agent.service;

import com.pams.module.agent.llm.LlmProvider;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in, stream-scoped evaluation accounting. Never retains prompts, tool arguments or keys. */
@Component
public class AgentEvaluationMeter {
    public static class LimitExceeded extends RuntimeException {
        public LimitExceeded() { super("评估模型调用预算已用尽"); }
    }
    private static class Session {
        final int maxCalls, maxTokens;
        int attempts, failures, degraded, reported;
        long input, output;
        final Set<String> models = new LinkedHashSet<>();
        final List<Map<String,Object>> calls = new ArrayList<>();
        Session(int maxCalls,int maxTokens) { this.maxCalls=maxCalls;this.maxTokens=maxTokens; }
    }
    private final Map<String,Session> active = new ConcurrentHashMap<>();
    public synchronized void open(String stream,int maxCalls,int maxTokens) {
        if(stream==null || maxCalls<1 || maxCalls>40 || maxTokens<128 || maxTokens>8192) throw new IllegalArgumentException("无效评估预算");
        if(active.size()>=32 || active.putIfAbsent(stream,new Session(maxCalls,maxTokens))!=null) throw new IllegalStateException("评估会话已存在或数量过多");
    }
    public int before(String stream,int requested) {
        Session s=stream==null?null:active.get(stream);
        if(s==null)return requested;
        synchronized(s) { if(s.attempts>=s.maxCalls)throw new LimitExceeded();s.attempts++;return Math.min(requested,s.maxTokens); }
    }
    public void record(String stream,LlmProvider.ChatResponse response,long elapsedMs,boolean failed) {
        Session s=stream==null?null:active.get(stream);if(s==null)return;
        synchronized(s) {
            var call=new LinkedHashMap<String,Object>();call.put("durationMs",elapsedMs);call.put("failed",failed);
            if(failed)s.failures++;
            if(response!=null) {
                if(response.degraded())s.degraded++;
                call.put("provider",response.provider());call.put("model",response.model());call.put("finishReason",response.finishReason());
                if(response.model()!=null)s.models.add(response.model());
                if(response.usage()!=null) { s.reported++;s.input+=response.usage().inputTokens();s.output+=response.usage().outputTokens();call.put("usage",response.usage()); }
            }
            s.calls.add(call);
        }
    }
    public Map<String,Object> snapshot(String stream) {
        Session s=active.get(stream);if(s==null)return Map.of();
        synchronized(s) {
            var m=new LinkedHashMap<String,Object>();m.put("attempts",s.attempts);m.put("failures",s.failures);m.put("degradedResponses",s.degraded);
            m.put("reportedCalls",s.reported);m.put("usageComplete",s.attempts>0 && s.reported==s.attempts && s.failures==0 && s.degraded==0);
            m.put("reportedInputTokens",s.input);m.put("reportedOutputTokens",s.output);m.put("cost",null);
            m.put("costStatus","UNKNOWN_PRICE_OR_INCOMPLETE_USAGE");m.put("models",List.copyOf(s.models));m.put("calls",List.copyOf(s.calls));
            m.put("maxCalls",s.maxCalls);m.put("perCallOutputTokenCap",s.maxTokens);m.put("maximumOutputTokens",s.maxCalls*s.maxTokens);
            return m;
        }
    }
    public Map<String,Object> close(String stream) { var result=snapshot(stream);active.remove(stream);return result; }
}
