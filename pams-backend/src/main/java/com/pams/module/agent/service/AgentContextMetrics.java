package com.pams.module.agent.service;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.llm.LlmProvider;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Process-local, owner-scoped aggregates. No prompts, preference text or conversation identifiers. */
@Service
public class AgentContextMetrics {
    private record Owner(Long org,Long user) {}
    private static class Counters {
        final long started=System.currentTimeMillis();
        final LongAdder calls=new LongAdder(),completed=new LongAdder(),failures=new LongAdder(),duration=new LongAdder(),bytes=new LongAdder(),
                writes=new LongAdder(),cache=new LongAdder(),discarded=new LongAdder(),rejected=new LongAdder(),reads=new LongAdder(),hits=new LongAdder(),
                selected=new LongAdder(),usageSamples=new LongAdder(),input=new LongAdder(),output=new LongAdder();
    }
    private final Map<Owner,Counters> users=new HashMap<>();
    private final ThreadLocal<Counters> active=new ThreadLocal<>();
    private synchronized Counters counters(ToolContext ctx) {
        Owner key=new Owner(ctx.getLoginUser().getOrgId(),ctx.getUserId());
        users.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().started>86400000L);
        if(!users.containsKey(key)&&users.size()>=1024)users.remove(users.entrySet().stream().min(Comparator.comparingLong(e->e.getValue().started)).orElseThrow().getKey());
        return users.computeIfAbsent(key,k->new Counters());
    }
    public <T> T summary(ToolContext ctx,int estimatedBytes,Supplier<T> call) {
        var c=counters(ctx);c.calls.increment();c.bytes.add(estimatedBytes);long started=System.nanoTime();
        var previous=active.get();active.set(c);
        try{return call.get();}catch(RuntimeException e){c.failures.increment();throw e;}
        finally{c.duration.add((System.nanoTime()-started)/1000000);c.completed.increment();if(previous==null)active.remove();else active.set(previous);}
    }
    public void usage(LlmProvider.Usage usage) {
        var c=active.get();if(c==null||usage==null)return;
        c.usageSamples.increment();c.input.add(usage.inputTokens());c.output.add(usage.outputTokens());
    }
    public void written(ToolContext ctx){counters(ctx).writes.increment();}
    public void cacheHit(ToolContext ctx){counters(ctx).cache.increment();}
    public void discarded(ToolContext ctx){counters(ctx).discarded.increment();}
    public void rejected(ToolContext ctx){counters(ctx).rejected.increment();}
    public void memoryRead(ToolContext ctx,int count){var c=counters(ctx);c.reads.increment();if(count>0)c.hits.increment();c.selected.add(count);}
    public Map<String,Object> snapshot(ToolContext ctx) {
        var c=counters(ctx);var out=new LinkedHashMap<String,Object>();
        out.put("windowStartedAt",Instant.ofEpochMilli(c.started).toString());out.put("processLocal",true);
        out.put("summaryCalls",c.calls.sum());out.put("summaryFailures",c.failures.sum());
        out.put("summaryRejected",c.rejected.sum());
        out.put("summaryFailureRate",c.calls.sum()==0?null:(double)(c.failures.sum()+c.rejected.sum())/c.calls.sum());
        out.put("summaryMeanMs",c.completed.sum()==0?null:(double)c.duration.sum()/c.completed.sum());
        out.put("summaryWrites",c.writes.sum());out.put("summaryCacheHits",c.cache.sum());out.put("summaryDiscarded",c.discarded.sum());
        out.put("estimatedInputBytes",c.bytes.sum());out.put("usageSamples",c.usageSamples.sum());
        out.put("inputTokens",c.usageSamples.sum()==0?null:c.input.sum());out.put("outputTokens",c.usageSamples.sum()==0?null:c.output.sum());
        out.put("memoryReads",c.reads.sum());out.put("memoryHits",c.hits.sum());out.put("selectedMemories",c.selected.sum());
        return out;
    }
}
