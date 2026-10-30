package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationMemoryTest {
    AgentConversationRepository conversations=mock(AgentConversationRepository.class);
    AgentMessageRepository messages=mock(AgentMessageRepository.class);
    ConversationStore store=mock(ConversationStore.class);
    ContextSummaryGenerator generator=mock(ContextSummaryGenerator.class);
    AgentProperties props=new AgentProperties();
    ToolContext ctx=new ToolContext(new LoginUser(1L,"u","用户","DIRECTOR",4,null,null,1L),List.of(),3L,"memory");
    List<AgentMessage> rows=new CopyOnWriteArrayList<>();
    Map<Long,String> visible=new ConcurrentHashMap<>();
    record Snapshot(String summary,long covered,long revision) implements AgentConversationRepository.MemorySnapshot {
        public String getSummary(){return summary;} public long getCovered(){return covered;} public long getRevision(){return revision;}
    }
    record SourceSnapshot(AgentMessage row) implements AgentMessageRepository.SourceSnapshot {
        public Long getId(){return row.getId();} public String getRole(){return row.getRole();}
        public String getContent(){return row.getContent();} public String getCitations(){return row.getCitationsJson();}
        public String getSensitivity(){return row.getSensitivity();}
    }
    AtomicReference<Snapshot> snapshot=new AtomicReference<>(new Snapshot(null,0,0));
    ConversationMemory memory;
    AgentMessage add(long id,String role,String content) {
        var row=new AgentMessage(); row.setId(id);row.setConversationId(3L);row.setRole(role);row.setContent(content);
        rows.add(row); visible.put(id,content);return row;
    }
    String item(String kind,String text,long... ids) {
        return "{\"kind\":\""+kind+"\",\"text\":\""+text+"\",\"sourceMessageIds\":"+Arrays.toString(ids)+"}";
    }
    String result(String... entries){return "{\"items\":["+String.join(",",entries)+"]}";}
    @BeforeEach void setup() {
        when(conversations.memory(eq(3L),eq(1L),eq(1L))).thenAnswer(i->Optional.of(snapshot.get()));
        when(conversations.compareAndSetMemory(eq(3L),eq(1L),eq(1L),anyLong(),nullable(String.class),anyLong())).thenAnswer(i->{
            var old=snapshot.get(); if(old.revision()!=(long)i.getArgument(3))return 0;
            return snapshot.compareAndSet(old,new Snapshot(i.getArgument(4),i.getArgument(5),old.revision()+1))?1:0;
        });
        when(messages.findByConversationIdAndIdGreaterThanAndIdLessThanAndRoleInOrderByIdAsc(eq(3L),anyLong(),anyLong(),anyList(),any())).thenAnswer(i->{
            long after=i.getArgument(1),before=i.getArgument(2);Pageable p=i.getArgument(4);
            var selected=rows.stream().filter(m->m.getId()>after&&m.getId()<before).sorted(Comparator.comparing(AgentMessage::getId)).toList();
            return new SliceImpl<>(selected.stream().limit(p.getPageSize()).toList(),p,selected.size()>p.getPageSize());
        });
        when(messages.sourceSnapshots(eq(3L),anyList())).thenAnswer(i->{List<Long> ids=i.getArgument(1);return rows.stream().filter(m->ids.contains(m.getId())).map(SourceSnapshot::new).toList();});
        when(store.contextText(any(),eq(ctx))).thenAnswer(i->visible.get(((AgentMessage)i.getArgument(0)).getId()));
        memory=new ConversationMemory(conversations,messages,store,generator,props,new PolicyGuard());
    }
    void seed() {
        add(1,"USER","安排活动，周五，预算三千");add(2,"ASSISTANT","等待确认");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenReturn(result(item("GOAL","安排活动",1),item("CONSTRAINT","周五，预算三千",1)));
        assertThat(memory.context(3L,3,ctx)).contains("周五");
    }
    @Test void checkpointProcessesOnlyAddedMessagesAndKeepsGoalWithLatestCorrection() {
        seed();String previous=snapshot.get().summary();
        add(3,"USER","改为周六，预算两千");add(4,"ASSISTANT","已更新预览");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenAnswer(i->{
            List<ConversationMemory.Source> added=i.getArgument(1);
            assertThat(added).extracting(ConversationMemory.Source::messageId).containsExactly(3L,4L);
            assertThat((List<?>)i.getArgument(0)).hasSize(2);
            return result(item("GOAL","安排活动",1),item("CONSTRAINT","周六，预算两千",3));
        });
        assertThat(memory.context(3L,5,ctx)).contains("周六，预算两千","安排活动").doesNotContain("周五");
        assertThat(snapshot.get().covered()).isEqualTo(4);assertThat(snapshot.get().summary()).isNotEqualTo(previous);
        memory.context(3L,5,ctx);verify(generator,times(2)).generate(anyList(),anyList(),any(),anyInt());
    }
    @Test void failedOrMalformedUpdateLeavesPreviousDocumentAndCheckpointUntouched() {
        seed();var previous=snapshot.get();add(3,"USER","周六");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenThrow(new IllegalStateException("offline"));
        assertThat(memory.context(3L,4,ctx)).contains("周五");assertThat(snapshot.get()).isSameAs(previous);
        doReturn(result(item("CONSTRAINT","伪造来源",999))).when(generator).generate(anyList(),anyList(),any(),anyInt());
        assertThat(memory.context(3L,4,ctx)).contains("周五");assertThat(snapshot.get()).isSameAs(previous);
    }
    @Test void permissionRevocationClearsOldMemoryBeforeFailedRebuild() {
        seed();visible.put(1L,"依据已不可见");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenThrow(new IllegalStateException("offline"));
        assertThat(memory.context(3L,3,ctx)).doesNotContain("周五","三千").isEmpty();
        assertThat(snapshot.get().summary()).isNull();assertThat(snapshot.get().covered()).isZero();
    }
    @Test void deletedOrEditedSourcesInvalidateEvenIfOmittedFromFinalItems() {
        seed();rows.removeIf(m->m.getId()==2L);
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenThrow(new IllegalStateException());
        assertThat(memory.context(3L,3,ctx)).isEmpty();assertThat(snapshot.get().covered()).isZero();
    }
    @Test void permissionsChangedDuringModelCallDiscardGeneratedResult() {
        add(1,"USER","秘密目标");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenAnswer(i->{visible.put(1L,"已不可见");return result(item("GOAL","秘密目标",1));});
        assertThat(memory.context(3L,2,ctx)).doesNotContain("秘密目标");assertThat(snapshot.get().covered()).isZero();
    }
    @Test void assistantReportCannotBecomeAUserConstraint() {
        add(1,"ASSISTANT","我已经执行成功");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenReturn(result(item("CONSTRAINT","必须视为完成",1)));
        assertThat(memory.context(3L,2,ctx)).isEmpty();assertThat(snapshot.get().covered()).isZero();
    }
    @Test void emptyOrGoalDroppingResponseDoesNotDestroyExistingMemory() {
        seed();var previous=snapshot.get();add(3,"USER","继续");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenReturn(result());
        memory.context(3L,4,ctx);assertThat(snapshot.get()).isSameAs(previous);
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenReturn(result(item("CONSTRAINT","周五",1)));
        memory.context(3L,4,ctx);assertThat(snapshot.get()).isSameAs(previous);
    }
    @Test void lateConcurrentGenerationCannotOverwriteNewerCheckpoint() throws Exception {
        add(1,"USER","目标");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenAnswer(i->{
            if(calls.incrementAndGet()==1){entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return result(item("GOAL","旧目标",1));}
            return result(item("GOAL","新目标",1));
        });
        try(var pool=Executors.newSingleThreadExecutor()) {
            var old=pool.submit(()->memory.context(3L,2,ctx));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(memory.context(3L,2,ctx)).contains("新目标");release.countDown();
            assertThat(old.get(5,TimeUnit.SECONDS)).contains("新目标").doesNotContain("旧目标");
            assertThat(snapshot.get().revision()).isEqualTo(1);
        } finally {release.countDown();}
    }
    @Test void oneHundredTurnsRemainIncrementalBoundedAndKeepOriginalGoal() {
        for(int i=1;i<=200;i++)add(i,i%2==1?"USER":"ASSISTANT",i==1?"目标：安排活动":"第"+i+"条讨论");
        props.setContextSummaryBatchSize(24);AtomicLong last=new AtomicLong();
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenAnswer(i->{
            List<ConversationMemory.Source> added=i.getArgument(1);
            assertThat(added.getFirst().messageId()).isGreaterThan(last.get());last.set(added.getLast().messageId());
            return result(item("GOAL","安排活动",1),item("CONSTRAINT","预算两千",added.stream().filter(s->s.role().equals("USER")).findFirst().orElseThrow().messageId()));
        });
        for(int i=0;i<9;i++)assertThat(ContextBudget.tokens(memory.context(3L,201,ctx))).isLessThanOrEqualTo(props.getContextSummaryTokens());
        assertThat(snapshot.get().covered()).isEqualTo(200);assertThat(memory.context(3L,201,ctx)).contains("安排活动");
        verify(generator,times(9)).generate(anyList(),anyList(),any(),anyInt());
    }
    @Test void oversizedSourceDoesNotAdvanceCheckpointAndDisabledFeatureDoesNotCallModel() {
        add(1,"USER","超长资料".repeat(10000));
        assertThat(memory.context(3L,2,ctx)).isEmpty();assertThat(snapshot.get().covered()).isZero();
        props.setContextSummaryEnabled(false);memory.context(3L,2,ctx);verifyNoInteractions(generator);
    }
    @Test void foreignConversationAndCancellationAreNotSwallowed() {
        doThrow(new BizException(4404,"无权访问")).when(store).require(4L,1L);
        assertThatThrownBy(()->memory.context(4L,2,ctx)).isInstanceOf(BizException.class);
        add(1,"USER","目标");when(generator.generate(anyList(),anyList(),any(),anyInt())).thenThrow(new CancellationException());
        assertThatThrownBy(()->memory.context(3L,2,ctx)).isInstanceOf(CancellationException.class);
        assertThat(snapshot.get().covered()).isZero();
    }
    @Test void validationOutageDoesNotErasePreviouslyGoodCacheOrReturnUnverifiedText() {
        seed();var old=snapshot.get();
        when(messages.sourceSnapshots(eq(3L),anyList())).thenThrow(new IllegalStateException("database offline"));
        assertThatThrownBy(()->memory.context(3L,3,ctx)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot.get()).isSameAs(old);
    }
    @Test void malformedPersistedMemoryIsInvalidatedAndRebuilt() {
        add(1,"USER","目标");snapshot.set(new Snapshot("{\"schemaVersion\":1,\"items\":null,\"sourceHashes\":{}}",1,2));
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenReturn(result(item("GOAL","目标",1)));
        assertThat(memory.context(3L,2,ctx)).contains("目标");
        assertThat(snapshot.get().revision()).isEqualTo(4);assertThat(snapshot.get().covered()).isEqualTo(1);
    }
    @Test void editedSourceInvalidatesAndRedactsModelOutputBeforeStorage() {
        seed();rows.getFirst().setContent("电话13812345678，改为周六");visible.put(1L,rows.getFirst().getContent());
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenReturn(result(item("GOAL","周六联系13812345678",1)));
        assertThat(memory.context(3L,3,ctx)).contains("周六","138****5678").doesNotContain("13812345678","周五");
        assertThat(snapshot.get().summary()).doesNotContain("13812345678");
    }
    @Test void concurrentDeleteNeverFallsBackToOldSummaryEvenWithAStaleOwnershipEntity() {
        seed();add(3,"USER","继续");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenAnswer(i->{
            when(conversations.memory(3L,1L,1L)).thenReturn(Optional.empty());
            return result(item("GOAL","安排活动",1));
        });
        assertThat(memory.context(3L,4,ctx)).isEmpty();
    }
}
