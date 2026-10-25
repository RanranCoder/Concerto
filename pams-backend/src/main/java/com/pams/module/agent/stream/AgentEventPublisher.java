package com.pams.module.agent.stream;

import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.entity.AgentToolCall;
import com.pams.module.agent.repository.AgentConversationRepository;
import com.pams.module.agent.repository.AgentToolCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 过程事件推送 + 断连补拉缓冲（业务设计方案 D7）。
 *
 * <p><b>为什么能复用现有 WebSocket</b>（2026-09-13 源码核实）：
 * {@code NotificationEventListener} 是一个普通 {@code @Component}，注入 {@code SimpMessagingTemplate}
 * 并直接调 {@code convertAndSendToUser(...)}，<b>无任何线程特判</b>——
 * 证明 {@code AgentOrchestrator} 在自己的线程池里推事件完全可行。
 *
 * <p><b>⚠️ 单实例是这套推送方案的生效前提</b>：{@code WebSocketConfig} 用的是
 * {@code enableSimpleBroker}（<b>内存 broker</b>），用户订阅关系只存在于当前 JVM。
 * 将来横向扩容后 {@code /user/queue/**} 会<b>静默失效</b>（不报错、只是收不到）。
 * 届时的解法（择一）：① STOMP broker relay；② Redis pub/sub；③ 会话粘滞。
 * 当前单实例前提成立，故不加任何代码，只记录边界。
 */
@Slf4j
@Component
public class AgentEventPublisher {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.pams.module.permission.service.TenantAuthorizationService authorization;
    private final Map<String,Long> authorizationVersions = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.pams.module.agent.service.AgentRequestService requests;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.pams.module.agent.llm.LlmCancellation cancellation;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ThinkingTrace trace;
    private final Map<String,com.pams.module.agent.entity.AgentRequest> identities=new ConcurrentHashMap<>();
    private final Map<String,Long> touched=new ConcurrentHashMap<>();
    private final java.util.Set<String> finished=ConcurrentHashMap.newKeySet();

    private boolean authorizedStream(String sid) {
        Long uid = owners.get(sid);
        if (authorization == null || uid == null) return true;
        Long baseline = authorizationVersions.get(sid);
        boolean allowed = authorization.valid(uid) && baseline != null && baseline.equals(((Number)authorization.identity(uid).get("authz_version")).longValue());
        if (!allowed) { aborted.put(sid,true); buffers.remove(sid); if(cancellation!=null) cancellation.abort(sid); }
        return allowed;
    }

    public static final String DEST_STREAM = "/queue/agent/stream";
    public static final String DEST_TASK = "/queue/agent";

    private final SimpMessagingTemplate messagingTemplate;
    private final AgentToolCallRepository toolCallRepository;
    private final AgentConversationRepository conversationRepository;
    private final AgentProperties props;

    /** streamId → 事件缓冲（供 GET /api/agent/streams/{id}?since={seq} 补拉） */
    private final Map<String, List<AgentStreamEvent>> buffers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    /** 已中止的流（abort 后编排器在下一个阶段边界退出） */
    private final Map<String, Boolean> aborted = new ConcurrentHashMap<>();
    /**
     * streamId → 归属用户 id（权限审计 A5）。
     *
     * <p>补拉接口 {@code GET /api/agent/streams/{streamId}} 此前只校验"已登录"、
     * 不校验"这个流是不是你的"。streamId 是 {@code hex(nanoTime())} 而非 UUID，
     * 可猜性高于随机串；一旦泄露（日志、截图、工单、Referer），别人就能读到
     * 你这一轮的过程事件。存归属是为了把那道门补上。
     */
    private final Map<String, Long> owners = new ConcurrentHashMap<>();

    public AgentEventPublisher(SimpMessagingTemplate messagingTemplate,
                               AgentToolCallRepository toolCallRepository,
                               AgentConversationRepository conversationRepository,
                               AgentProperties props) {
        this.messagingTemplate = messagingTemplate;
        this.toolCallRepository = toolCallRepository;
        this.conversationRepository = conversationRepository;
        this.props = props;
    }

    /**
     * 新建一个流，返回 streamId。
     *
     * @param ownerUserId 归属用户；供补拉时的归属校验用。
     *                    传 {@code null} 表示"无归属信息"（此时补拉会走数据库回查，
     *                    回查也拿不到就拒绝 —— 宁可少给，也不要放行一条无法确认归属的流）
     */
    private final Map<String,Long> creationOrder=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong streamOrder=new java.util.concurrent.atomic.AtomicLong();
    private String oldestStream() {
        return touched.entrySet().stream().min(java.util.Comparator.<Map.Entry<String,Long>>comparingLong(Map.Entry::getValue).thenComparingLong(e->creationOrder.getOrDefault(e.getKey(),0L))).orElseThrow().getKey();
    }

    public synchronized String newStream(String prefix, Long ownerUserId) {
        prune();
        if(touched.size()>=Math.max(1,props.getStreamCacheMaxEntries())) evict(oldestStream());
        String id = (prefix == null ? "s" : prefix) + "_" + java.util.UUID.randomUUID();
        touched.put(id,System.currentTimeMillis());
        creationOrder.put(id,streamOrder.incrementAndGet());
        counters.put(id, new AtomicInteger(0));
        buffers.put(id, new ArrayList<>());
        if (ownerUserId != null) {
            owners.put(id, ownerUserId);
            if (authorization != null) authorizationVersions.put(id,((Number)authorization.identity(ownerUserId).get("authz_version")).longValue());
        }
        return id;
    }

    public synchronized int nextSeq(String streamId) {
        if (streamId == null) return 0;
        if(!touched.containsKey(streamId)) return 0;
        return counters.computeIfAbsent(streamId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * 推送事件：先入缓冲（保证补拉可取），再推 WebSocket。
     *
     * @param username 目标用户（用户定向队列）
     */
    public synchronized void publish(String username, AgentStreamEvent event) {
        if (event == null) return;
        // 1) 缓冲（streamId 为空时不缓冲——例如协作基准等非会话场景，避免 ConcurrentHashMap 空键 NPE）
        String sid = event.streamId();
        if (sid != null && !authorizedStream(sid)) return;
        if(sid!=null && !touched.containsKey(sid)) return; // Evicted/unknown streams cannot resurrect cache entries.
        if(sid!=null) {
            touched.put(sid,System.currentTimeMillis());
            var identity=identities.get(sid);
            if(identity!=null) {
                var payload=new java.util.LinkedHashMap<>(event.payload());
                payload.put("requestId",identity.getId()); payload.put("conversationId",identity.getConversationId());
                if(identity.getRunId()!=null) payload.put("runId",identity.getRunId());
                payload.putIfAbsent("channel","final"); payload.put("protocolVersion",2);
                event=new AgentStreamEvent(event.streamId(),event.seq(),event.type(),payload,event.ts());
            }
        }
        if (sid != null) {
            List<AgentStreamEvent> buf = buffers.computeIfAbsent(sid, k -> new ArrayList<>());
            synchronized (buf) {
                buf.add(event);
                if (buf.size() > props.getStreamBufferSize()) {
                    buf.remove(0);
                }
            }
        }
        // 2) 推送（失败不阻断编排——推送是增值能力，不是主链路）
        try {
            messagingTemplate.convertAndSendToUser(username, DEST_STREAM, event);
        } catch (Exception e) {
            log.warn("灵思过程事件推送失败：stream={} type={}", event.streamId(), event.type(), e);
        }
        if(sid!=null && java.util.Set.of("MESSAGE_DONE","ERROR","ABORTED").contains(event.type())) {
            finished.add(sid);
            if(requests!=null && identities.containsKey(sid)) requests.finish(sid,"ABORTED".equals(event.type())?"CANCELLED":"ERROR".equals(event.type())?"FAILED":"SUCCESS");
        }
    }

    /**
     * 补拉：返回 seq > since 的事件（D7 降级通道 / 重连补齐）。
     *
     * <p><b>归属校验（权限审计 A5）</b>：只返回属于 {@code requesterUserId} 的流。
     * 此前这里只要求"已登录"，而 streamId 是 {@code hex(nanoTime())} 而非 UUID ——
     * 一旦通过日志/截图/工单泄露，任何人都能读到别人的过程事件。
     */
    public List<AgentStreamEvent> since(String streamId, int since, Long requesterUserId) {
        if (!authorizedStream(streamId)) return List.of();
        if (!ownedBy(streamId, requesterUserId)) {
            log.warn("灵思过程事件补拉被拒：stream={} requester={}", streamId, requesterUserId);
            // 返回空而不是抛异常：调用方拿到空列表即"没有可补的事件"，
            // 不额外暴露"这个流存在但不是你的"（存在性泄露，方案 G7 的同一条思路）
            return List.of();
        }
        List<AgentStreamEvent> buf = buffers.get(streamId);
        if (buf == null) {
            // 缓冲已被清理（如服务重启）→ 从 agent_tool_call 表重建工具行（灵思 §7.1 的历史回看）
            return rebuildFromDb(streamId, since);
        }
        synchronized (buf) {
            return buf.stream()
                    .filter(e -> e.seq() > since)
                    .sorted(Comparator.comparingInt(AgentStreamEvent::seq))
                    .toList();
        }
    }

    /**
     * 该流是否属于请求者。
     *
     * <p>两级判定：内存映射 → 数据库回查。
     * 回查是必需的：进程重启后内存里的 {@code owners} 是空的，
     * 而"重启后补拉历史过程"正是这个接口存在的理由之一 ——
     * 只靠内存判定会把合法请求一起拒掉。
     */
    private boolean ownedBy(String streamId, Long requesterUserId) {
        if (streamId == null || requesterUserId == null) return false;

        Long owner = owners.get(streamId);
        if (owner != null) return owner.equals(requesterUserId);
        if(requests!=null) {
            var durable=requests.byStream(streamId);
            if(durable.isPresent()) return requesterUserId.equals(durable.get().getUserId()) && java.util.Objects.equals(com.pams.common.OrgContext.get(),durable.get().getOrgId()) && requests.live(durable.get());
        }

        // 内存里没有 → 回查：流的第一条工具调用 → conversationId → 会话的 userId
        try {
            return toolCallRepository.findFirstByStreamIdOrderBySeqAsc(streamId)
                    .map(AgentToolCall::getConversationId)
                    .flatMap(conversationRepository::findById)
                    .map(c -> requesterUserId.equals(c.getUserId()))
                    .orElse(false);
        } catch (Exception e) {
            log.warn("灵思流归属回查失败，按拒绝处理：stream={}", streamId, e);
            return false;
        }
    }

    /** 服务重启后从 agent_tool_call 表重建过程区（证明过程是真实历史，不是前端临时状态） */
    private List<AgentStreamEvent> rebuildFromDb(String streamId, int since) {
        List<AgentToolCall> rows = toolCallRepository.findByStreamIdOrderBySeqAsc(streamId);
        List<AgentStreamEvent> out = new ArrayList<>();
        for (AgentToolCall r : rows) {
            if (r.getSeq() == null || r.getSeq() <= since) continue;
            out.add(AgentStreamEvent.toolEnd(streamId, r.getSeq(), r.getToolName(), r.getLabel(),
                    r.getResultStatus(), r.getDurationMs() == null ? 0 : r.getDurationMs(), r.getErrorMessage()));
        }
        return out;
    }

    /** 记录工具调用流水（同时是过程区的持久化数据源） */
    public void recordToolCall(Long conversationId, Long messageId, String streamId, int seq,
                               Long userId, String toolName, String label, String argsJson,
                               String sensitivity, String sideEffect,
                               long durationMs, String resultStatus, String error) {
        try {
            AgentToolCall row = new AgentToolCall();
            row.setConversationId(conversationId);
            row.setMessageId(messageId);
            row.setStreamId(streamId);
            row.setSeq(seq);
            row.setUserId(userId == null ? 0L : userId);
            row.setToolName(toolName);
            row.setLabel(label);
            row.setArgsJson(argsJson);
            row.setSensitivity(sensitivity == null ? "normal" : sensitivity);
            row.setSideEffect(sideEffect == null ? "READ" : sideEffect);
            row.setDurationMs((int) durationMs);
            row.setResultStatus(resultStatus);
            row.setErrorMessage(error);
            row.setCreatedAt(LocalDateTime.now());
            toolCallRepository.save(row);
        } catch (Exception e) {
            log.warn("灵思工具调用流水写入失败：tool={}", toolName, e);
        }
    }

    public synchronized void markAborted(String streamId) {
        if(streamId==null) return;
        if(touched.containsKey(streamId)) aborted.put(streamId, Boolean.TRUE);
        if(cancellation!=null) cancellation.abort(streamId);
    }

    /** Only a stream's owner may interrupt it; unknown IDs never allocate state. */
    public boolean abortOwned(String streamId, Long requesterUserId) {
        if (!ownedBy(streamId, requesterUserId)) return false;
        if(requests!=null) requests.byStream(streamId).ifPresent(r->requests.cancel(r.getId(),requesterUserId));
        markAborted(streamId);
        return true;
    }

    public boolean isAborted(String streamId) {
        if(streamId==null) return false;
        if(Boolean.TRUE.equals(aborted.get(streamId))) return true;
        if(identities.containsKey(streamId)) return identities.get(streamId).isCancelled();
        return requests!=null && requests.byStream(streamId).map(com.pams.module.agent.entity.AgentRequest::isCancelled).orElse(false);
    }

    public synchronized void clear(String streamId) {
        if(streamId!=null && touched.containsKey(streamId)) { finished.add(streamId); touched.put(streamId,System.currentTimeMillis()); }
    }

    /** 异步任务完成推送（D7 长任务，前端 useWebSocket 加一个订阅即可） */
    public void publishTaskDone(String username, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSendToUser(username, DEST_TASK, payload);
        } catch (Exception e) {
            log.warn("灵思任务完成推送失败：{}", username, e);
        }
    }
    /** Outbox delivery must observe broker failure instead of acknowledging a swallowed exception. */
    public void publishDurable(String username,Map<String,Object> payload) { messagingTemplate.convertAndSendToUser(username,DEST_TASK,payload); }
    public com.pams.module.agent.core.ToolContext bind(String stream,com.pams.module.agent.core.ToolContext ctx) {
        String request=ctx.getRequestId()==null?java.util.UUID.randomUUID().toString():ctx.getRequestId();
        var bound=ctx.derive(ctx.getConversationId(),stream).withRequestId(request);
        if(requests!=null) identities.put(stream,requests.begin(request,stream,bound));
        return bound;
    }
    public synchronized void identify(com.pams.module.agent.core.ToolContext ctx) {
        if(requests==null || ctx.getStreamId()==null || !touched.containsKey(ctx.getStreamId())) return;
        requests.link(ctx.getStreamId(),ctx.getConversationId(),ctx.getRunId());
        requests.byStream(ctx.getStreamId()).ifPresent(r->identities.put(ctx.getStreamId(),r));
    }
    public Map<String,Object> replay(String stream,int since,Long uid) {
        prune();
        if(!ownedBy(stream,uid)) throw new com.pams.common.BizException(404,"请求不存在");
        var events=buffers.containsKey(stream)?since(stream,Math.max(0,since),uid):List.<AgentStreamEvent>of();
        var result=new java.util.LinkedHashMap<String,Object>(); result.put("streamId",stream); result.put("events",events);
        var buffer=buffers.get(stream);
        boolean gap=buffer==null;
        if(buffer!=null) synchronized(buffer) { gap=!buffer.isEmpty() && since<buffer.getFirst().seq()-1; }
        result.put("requiresSnapshot",gap); result.put("lastSeq",counters.containsKey(stream)?counters.get(stream).get():0);
        if(requests!=null) requests.byStream(stream).ifPresent(r->{ result.put("requestId",r.getId());result.put("runId",r.getRunId());result.put("conversationId",r.getConversationId());result.put("status",r.getStatus()); });
        return result;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000)
    public synchronized void prune() {
        long now=System.currentTimeMillis();
        for(var entry:List.copyOf(touched.entrySet())) if(now-entry.getValue()>(finished.contains(entry.getKey())?props.getStreamCacheTtlMs():props.getStreamActiveTtlMs())) evict(entry.getKey());
        while(touched.size()>Math.max(1,props.getStreamCacheMaxEntries())) {
            evict(oldestStream());
        }
    }
    private void evict(String stream) {
        if(trace!=null) trace.clear(stream);
        buffers.remove(stream); counters.remove(stream); owners.remove(stream); authorizationVersions.remove(stream);
        aborted.remove(stream); identities.remove(stream); touched.remove(stream); finished.remove(stream); creationOrder.remove(stream);
    }
    public int cachedStreamCount() { return touched.size(); }
}
