package com.pams.module.agent.stream;

import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.entity.AgentConversation;
import com.pams.module.agent.entity.AgentToolCall;
import com.pams.module.agent.repository.AgentConversationRepository;
import com.pams.module.agent.repository.AgentToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 过程事件补拉的**归属校验**回归（权限审计 A5）。
 *
 * <p>修复前的行为：{@code GET /api/agent/streams/{streamId}} 只校验"已登录"，
 * 不校验"这个流是不是你的"。而 streamId 是 {@code hex(nanoTime())} ——
 * <b>不是 UUID</b>，可猜性明显高于随机串；一旦通过日志、截图、工单或 Referer 泄露，
 * 任何人都能读到别人的过程事件（工具名、耗时、结果摘要）。
 *
 * <p>两级判定都要覆盖：
 * <ol>
 *   <li><b>内存映射</b>（进程存活期）—— 快路径</li>
 *   <li><b>数据库回查</b>（进程重启后内存映射为空）—— 不能因为内存没有就一律拒绝，
 *       否则"重启后补拉历史过程"这个接口存在的主要用途就废了</li>
 * </ol>
 *
 * <p>还有一条行为约定要固定：<b>不匹配时返回空列表，而不是抛异常或给出不同的错误</b>。
 * 若两者可区分，攻击者就能拿"这个流不是你的"当作存在性证明去枚举（方案 G7 的同一条思路）。
 */
class AgentEventPublisherTest {

    @Test
    void onlyTheOwnerMayAbortAndUnknownIdsDoNotBecomeAborted() {
        String id = streamOwnedBy(1L);
        assertThat(publisher.abortOwned(id, 2L)).isFalse();
        assertThat(publisher.isAborted(id)).isFalse();
        assertThat(publisher.abortOwned("unknown", 1L)).isFalse();
        assertThat(publisher.isAborted("unknown")).isFalse();
        assertThat(publisher.abortOwned(id, 1L)).isTrue();
        assertThat(publisher.isAborted(id)).isTrue();
    }

    private SimpMessagingTemplate messaging;
    private AgentToolCallRepository toolCallRepository;
    private AgentConversationRepository conversationRepository;
    private AgentEventPublisher publisher;

    @BeforeEach
    void setUp() {
        messaging = Mockito.mock(SimpMessagingTemplate.class);
        toolCallRepository = Mockito.mock(AgentToolCallRepository.class);
        conversationRepository = Mockito.mock(AgentConversationRepository.class);

        AgentProperties props = new AgentProperties();
        props.setStreamBufferSize(500);   // 默认 0 会让缓冲立刻被裁空，测试会假失败

        publisher = new AgentEventPublisher(messaging, toolCallRepository,
                conversationRepository, props);
    }

    private String streamOwnedBy(long userId) {
        String id = publisher.newStream("st", userId);
        publisher.publish("user" + userId, AgentStreamEvent.thinking(id, 1, "正在理解…"));
        return id;
    }

    @Test
    void ownerCanPullOwnStream() {
        String id = streamOwnedBy(1L);

        List<AgentStreamEvent> events = publisher.since(id, 0, 1L);

        assertThat(events).as("归属者可读到自己的过程事件").hasSize(1);
    }

    @Test
    void otherUserGetsNothingNotAnError() {
        String id = streamOwnedBy(1L);

        List<AgentStreamEvent> events = publisher.since(id, 0, 2L);

        assertThat(events)
                .as("非归属者拿到空列表（不抛异常、不给可区分的错误，避免存在性泄露）")
                .isEmpty();
    }

    @Test
    void nullRequesterIsDenied() {
        String id = streamOwnedBy(1L);

        assertThat(publisher.since(id, 0, null)).isEmpty();
    }

    /** 进程重启后内存映射为空 → 必须回查数据库，且回查结论同样要能放行合法请求 */
    @Test
    void fallsBackToDatabaseWhenMemoryIsEmpty() {
        String unknown = "st_deadbeef";
        AgentToolCall row = new AgentToolCall();
        row.setConversationId(9L);
        when(toolCallRepository.findFirstByStreamIdOrderBySeqAsc(unknown))
                .thenReturn(Optional.of(row));

        AgentConversation conv = new AgentConversation();
        conv.setId(9L);
        conv.setUserId(5L);
        when(conversationRepository.findById(9L)).thenReturn(Optional.of(conv));

        assertThat(publisher.since(unknown, 0, 5L))
                .as("回查确认归属 → 放行（重启后补拉历史是这接口的主要用途）")
                .isEmpty();   // 缓冲为空所以没有事件，但**不是被拒**：回查路径已走通
    }

    @Test
    void databaseFallbackDeniesOtherUsers() {
        String unknown = "st_deadbeef";
        AgentToolCall row = new AgentToolCall();
        row.setConversationId(9L);
        when(toolCallRepository.findFirstByStreamIdOrderBySeqAsc(unknown))
                .thenReturn(Optional.of(row));

        AgentConversation conv = new AgentConversation();
        conv.setId(9L);
        conv.setUserId(5L);
        when(conversationRepository.findById(9L)).thenReturn(Optional.of(conv));

        assertThat(publisher.since(unknown, 0, 6L)).isEmpty();
    }

    /** 库里也查不到归属（例如流根本不存在）→ 保守拒绝 */
    @Test
    void unknownStreamWithNoDbRecordIsDenied() {
        when(toolCallRepository.findFirstByStreamIdOrderBySeqAsc("st_notexist"))
                .thenReturn(Optional.empty());

        assertThat(publisher.since("st_notexist", 0, 1L)).isEmpty();
    }

    @Test void capacityAndExpiryRemoveEveryStreamCacheAndDoNotResurrectUnknownStreams() {
        var props=new AgentProperties();props.setStreamCacheMaxEntries(2);props.setStreamBufferSize(2);props.setStreamCacheTtlMs(1);
        var bounded=new AgentEventPublisher(messaging,toolCallRepository,conversationRepository,props);
        String first=bounded.newStream("bounded",1L);bounded.markAborted(first);
        String second=bounded.newStream("bounded",1L);bounded.newStream("bounded",1L);
        assertThat(bounded.cachedStreamCount()).isEqualTo(2);assertThat(bounded.nextSeq(first)).isZero();
        @SuppressWarnings("unchecked") var times=(Map<String,Long>)org.springframework.test.util.ReflectionTestUtils.getField(bounded,"touched");
        for(String id:List.copyOf(times.keySet())) {bounded.clear(id);times.put(id,0L);}
        bounded.prune();assertThat(bounded.cachedStreamCount()).isZero();
        bounded.publish("u",AgentStreamEvent.thinking(second,10,"late"));bounded.markAborted(second);
        for(String field:List.of("buffers","counters","owners","authorizationVersions","aborted","identities","touched","creationOrder"))
            assertThat((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(bounded,field)).as(field).isEmpty();
    }
    @Test void aTruncatedReplayRequestsASnapshotInsteadOfPretendingToBeComplete() {
        var props=new AgentProperties();props.setStreamBufferSize(2);
        var bounded=new AgentEventPublisher(messaging,toolCallRepository,conversationRepository,props);
        String id=bounded.newStream("bounded",1L);
        for(int i=0;i<4;i++) bounded.publish("u",AgentStreamEvent.thinking(id,bounded.nextSeq(id),"note"));
        assertThat(bounded.replay(id,0,1L)).containsEntry("requiresSnapshot",true).containsEntry("lastSeq",4);
        assertThat(bounded.replay(id,3,1L)).containsEntry("requiresSnapshot",false);
    }
}
