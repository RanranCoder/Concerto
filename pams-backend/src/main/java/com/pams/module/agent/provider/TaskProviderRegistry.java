package com.pams.module.agent.provider;

import com.pams.common.OrgContext;
import com.pams.module.agent.llm.LlmProvider;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshot once per server-generated stream; all specialists and compactions share it. */
@Component
public class TaskProviderRegistry {
    private final AgentProviderService configs;
    private final LlmProvider deployment;
    private record Snapshot(long org, LlmProvider provider) {}
    private final ConcurrentHashMap<String,Snapshot> active = new ConcurrentHashMap<>();
    public TaskProviderRegistry(AgentProviderService configs, LlmProvider primaryProvider) { this.configs = configs; this.deployment = primaryProvider; }
    public LlmProvider begin(String stream, long org) {
        if (stream == null) return configs.resolve(org, deployment);
        var snapshot = active.computeIfAbsent(stream, k -> new Snapshot(org, configs.resolve(org, deployment, stream)));
        if (snapshot.org != org) throw new com.pams.common.BizException(403, "任务组织不一致");
        return snapshot.provider;
    }
    public LlmProvider get(String stream) {
        var snapshot = stream == null ? null : active.get(stream);
        if (snapshot != null) return snapshot.provider;
        Long org = OrgContext.get();
        if (org == null) throw new IllegalStateException("模型调用缺少任务组织上下文");
        return begin(stream, org);
    }
    public void release(String stream) { if (stream != null) active.remove(stream); }
}
