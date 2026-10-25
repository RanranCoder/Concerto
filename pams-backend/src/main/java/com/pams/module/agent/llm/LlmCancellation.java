package com.pams.module.agent.llm;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Each model exchange owns its transport and worker. Cancellation never interrupts a business commit thread. */
@Component
public class LlmCancellation {
    private static final ThreadLocal<Call> CURRENT=new ThreadLocal<>();
    private final ConcurrentMap<String,Set<Call>> active=new ConcurrentHashMap<>();
    private static final class Call {
        private volatile boolean cancelled;
        private Future<?> future;
        private final List<Runnable> hooks=new ArrayList<>();
        synchronized void hook(Runnable hook) { if(cancelled) hook.run(); else hooks.add(hook); }
        synchronized void cancel() {
            if(cancelled) return; cancelled=true;
            if(future!=null) future.cancel(true);
            for(var hook:hooks) try { hook.run(); } catch(Exception ignored) {}
            hooks.clear();
        }
    }
    public static void onCancel(Runnable hook) { var call=CURRENT.get(); if(call!=null) call.hook(hook); }
    public static void check() { var call=CURRENT.get(); if(Thread.currentThread().isInterrupted() || call!=null && call.cancelled) throw new CancellationException("模型调用已停止"); }
    public <T> T call(String stream,BooleanSupplier aborted,long timeoutMs,Supplier<T> operation) {
        if(aborted.getAsBoolean()) throw new CancellationException();
        var call=new Call(); String key=stream==null?UUID.randomUUID().toString():stream;
        active.compute(key,(k,group)->{ if(group==null) group=ConcurrentHashMap.newKeySet(); group.add(call); return group; });
        var future=new FutureTask<T>(()->{ CURRENT.set(call); try { check(); return operation.get(); } finally { CURRENT.remove(); } });
        synchronized(call) { call.future=future; }
        Thread.ofVirtual().name("agent-model").start(future);
        if(aborted.getAsBoolean()) call.cancel();
        try { return future.get(Math.max(1,timeoutMs),TimeUnit.MILLISECONDS); }
        catch(TimeoutException e) { call.cancel(); throw new IllegalStateException("模型调用超时",e); }
        catch(InterruptedException e) { call.cancel(); Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof CancellationException cancelled) throw cancelled;
            if(e.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(e.getCause());
        } finally { active.computeIfPresent(key,(k,v)->{v.remove(call);return v.isEmpty()?null:v;}); }
    }
    public void abort(String stream) { if(stream!=null) for(var call:active.getOrDefault(stream,Set.of())) call.cancel(); }
    public int activeCount() { return active.values().stream().mapToInt(Set::size).sum(); }
}
