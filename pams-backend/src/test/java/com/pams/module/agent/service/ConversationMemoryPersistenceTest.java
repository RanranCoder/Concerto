package com.pams.module.agent.service;

import com.pams.common.OrgContext;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.knowledge.service.PermissionResolver;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConversationMemoryPersistenceTest {
    @Autowired ConversationMemory memory;
    @Autowired ConversationStore store;
    @Autowired AgentConversationRepository conversations;
    @Autowired AgentMessageRepository messages;
    @Autowired JdbcTemplate db;
    @MockitoBean ContextSummaryGenerator generator;
    @MockitoSpyBean PermissionResolver permissions;
    @BeforeEach void tenant(){OrgContext.set(1L);}
    @AfterEach void clear(){OrgContext.clear();}
    ToolContext ctx(Long id){return new ToolContext(new LoginUser(1L,"u","用户","DIRECTOR",4,null,null,1L),List.of(),id,"summary-test");}
    AgentMessage add(Long conversation,String role,String text) {
        var m=new AgentMessage();m.setConversationId(conversation);m.setRole(role);m.setContent(text);return messages.saveAndFlush(m);
    }
    String summary(Long id){return "{\"items\":[{\"kind\":\"GOAL\",\"text\":\"安排周六活动\",\"sourceMessageIds\":["+id+"]}]}";}
    @Test void migratedColumnsPersistAtomicCheckpointRejectStaleWritersAndPurgeOnDelete() {
        var c=store.create(1L,"安排活动");var user=add(c.getId(),"USER","安排周六活动");var answer=add(c.getId(),"ASSISTANT","等待确认");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenReturn(summary(user.getId()));
        assertThat(memory.context(c.getId(),Long.MAX_VALUE,ctx(c.getId()))).contains("安排周六活动");
        var snapshot=conversations.memory(c.getId(),1L,1L).orElseThrow();
        assertThat(snapshot.getCovered()).isEqualTo(answer.getId());assertThat(snapshot.getRevision()).isEqualTo(1);
        assertThat(snapshot.getSummary()).contains("sourceHashes","安排周六活动");
        assertThat(conversations.compareAndSetMemory(c.getId(),1L,1L,0,"stale",0)).isZero();
        assertThat(conversations.compareAndSetMemory(c.getId(),2L,1L,1,"foreign",0)).isZero();
        assertThat(conversations.compareAndSetMemory(c.getId(),1L,2L,1,"foreign-org",0)).isZero();
        memory.context(c.getId(),Long.MAX_VALUE,ctx(c.getId()));verify(generator).generate(anyList(),anyList(),any(),anyInt());
        store.delete(c.getId(),1L);
        assertThat(conversations.memory(c.getId(),1L,1L)).isEmpty();
        assertThat(db.queryForObject("select context_summary_json from agent_conversation where id=?",String.class,c.getId())).isNull();
        assertThat(conversations.compareAndSetMemory(c.getId(),1L,1L,2,"resurrect",100)).isZero();
    }
    @Test void realCitationRevocationClearsPersistedSummaryAndDoesNotReuseItAfterModelFailure() {
        var c=store.create(1L,"安排活动");var user=add(c.getId(),"USER","安排周六活动");var answer=add(c.getId(),"ASSISTANT","据资料确认安排");
        answer.setCitationsJson("[{\"sourceType\":\"activity_plan\",\"sourceId\":9}]");messages.saveAndFlush(answer);
        doReturn(true).when(permissions).canAccess(eq("activity_plan"),eq(9L),any());
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenReturn(summary(user.getId()));
        assertThat(memory.context(c.getId(),Long.MAX_VALUE,ctx(c.getId()))).contains("安排周六活动");
        doReturn(false).when(permissions).canAccess(eq("activity_plan"),eq(9L),any());
        doThrow(new IllegalStateException("offline")).when(generator).generate(anyList(),anyList(),any(),anyInt());
        assertThat(memory.context(c.getId(),Long.MAX_VALUE,ctx(c.getId()))).isEmpty();
        var snapshot=conversations.memory(c.getId(),1L,1L).orElseThrow();
        assertThat(snapshot.getSummary()).isNull();assertThat(snapshot.getCovered()).isZero();assertThat(snapshot.getRevision()).isEqualTo(2);
    }
    @Test void conversationDeletedDuringGenerationCannotBeResurrectedByLateResult() {
        var c=store.create(1L,"安排活动");var user=add(c.getId(),"USER","安排周六活动");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenAnswer(i->{store.delete(c.getId(),1L);return summary(user.getId());});
        assertThat(memory.context(c.getId(),Long.MAX_VALUE,ctx(c.getId()))).isEmpty();
        assertThat(db.queryForObject("select context_summary_json from agent_conversation where id=?",String.class,c.getId())).isNull();
    }
    @Test void sourceEditDuringGenerationIsDetectedEvenIfJpaAlreadyLoadedTheOldEntity() {
        var c=store.create(1L,"安排活动");var user=add(c.getId(),"USER","安排周六活动");
        when(generator.generate(anyList(),anyList(),any(),anyInt())).thenAnswer(i->{
            db.update("update agent_message set content=? where id=?","改成周日",user.getId());
            return summary(user.getId());
        });
        assertThat(memory.context(c.getId(),Long.MAX_VALUE,ctx(c.getId()))).isEmpty();
        assertThat(conversations.memory(c.getId(),1L,1L).orElseThrow().getCovered()).isZero();
    }
}
