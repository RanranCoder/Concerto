package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.knowledge.service.PermissionResolver;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextAssemblerTest {
    AgentMessage message(long id, String role, String content) {
        var m = new AgentMessage(); m.setId(id); m.setConversationId(3L); m.setRole(role); m.setContent(content); return m;
    }
    ToolContext ctx() { return new ToolContext(new LoginUser(1L,"user","用户","DIRECTOR",4,null,null,1L),List.of(),3L,"stream"); }

    @Test void historySelectsUserBoundariesAndNeverRequestsToolRows() {
        var repo = mock(AgentMessageRepository.class);
        var store = new ConversationStore(mock(AgentConversationRepository.class),repo,mock(AgentTaskRepository.class),new PolicyGuard(),mock(PermissionResolver.class));
        var u1=message(1,"USER","早期" ); var a1=message(2,"ASSISTANT","回答");
        var u2=message(3,"USER","近期" ); var a2=message(4,"ASSISTANT","近期回答"); var now=message(5,"USER","本轮");
        when(repo.findByConversationIdAndRoleInOrderByIdDesc(eq(3L),eq(List.of("USER","ASSISTANT")),any()))
                .thenReturn(new PageImpl<>(List.of(now,a2,u2,a1,u1)));
        assertThat(store.context(3L,1)).containsExactly(u2,a2,now);
        verify(repo,never()).findTop20ByConversationIdOrderByIdDesc(any());
    }
    @Test void assemblesLiveStateAndSourceLinkedMemoryWithoutDuplicatingCurrentMessage() {
        var store = mock(ConversationStore.class); var gate=mock(TaskGate.class);
        var old=message(1,"USER","最早目标"); var recent=message(9,"USER","预算改成两千");
        when(store.context(3L,6)).thenReturn(List.of(recent));
        when(store.memorySources(3L,9L)).thenReturn(List.of(old));
        when(store.contextText(eq(old),any())).thenReturn("最早目标");
        when(store.taskState(3L,1L)).thenReturn("taskId=4 status=CANCELLED artifactVersion=2");
        when(gate.pendingPlanNote(3L,1L)).thenReturn("目前无待确认操作");
        var actual=new ContextAssembler(store,new AgentProperties(),gate).assemble("规则",3L,recent.getContent(),ctx(),"附件证据");
        assertThat(actual).filteredOn(m->recent.getContent().equals(m.content())).hasSize(1);
        assertThat(actual).anyMatch(m->m.content()!=null && m.content().contains("messageId=1"));
        assertThat(actual).anyMatch(m->m.content()!=null && m.content().contains("status=CANCELLED"));
        assertThat(actual).anyMatch(m->"user".equals(m.role()) && m.content().contains("附件证据"));
        verify(store).require(3L,1L);
    }
    @Test void recallRechecksRevokedEvidenceAndRejectsForeignConversation() {
        var conversations=mock(AgentConversationRepository.class); var messages=mock(AgentMessageRepository.class);
        var permissions=mock(PermissionResolver.class);
        var store=new ConversationStore(conversations,messages,mock(AgentTaskRepository.class),new PolicyGuard(),permissions);
        var conversation=new AgentConversation(); conversation.setId(3L); conversation.setUserId(1L);
        when(conversations.findByIdAndUserIdAndDeleted(3L,1L,0)).thenReturn(Optional.of(conversation));
        var old=message(1,"ASSISTANT","已撤权秘密正文");
        old.setCitationsJson("[{\"sourceType\":\"activity_plan\",\"sourceId\":9}]");
        when(messages.findByConversationIdAndIdLessThanAndContentContainingIgnoreCaseAndRoleInOrderByIdDesc(eq(3L),eq(Long.MAX_VALUE),eq("正文"),any(),any()))
                .thenReturn(new PageImpl<>(List.of(old)));
        assertThat(store.recall(3L,1L,ctx(),"正文")).doesNotContain("已撤权秘密正文").contains("已不可见");
        assertThatThrownBy(()->store.recall(4L,1L,ctx(),"正文")).isInstanceOf(BizException.class);
        verify(messages,never()).findByConversationIdAndIdLessThanAndContentContainingIgnoreCaseAndRoleInOrderByIdDesc(eq(4L),any(),any(),any(),any());
    }
    @Test void fullMessageReadsAreConversationBoundAndUseUnicodeOffsets() {
        var conversations=mock(AgentConversationRepository.class); var messages=mock(AgentMessageRepository.class);
        var conversation=new AgentConversation(); conversation.setId(3L); conversation.setUserId(1L);
        when(conversations.findByIdAndUserIdAndDeleted(3L,1L,0)).thenReturn(Optional.of(conversation));
        var store=new ConversationStore(conversations,messages,mock(AgentTaskRepository.class),new PolicyGuard(),mock(PermissionResolver.class));
        var old=message(8,"USER","😀".repeat(301));
        when(messages.findByIdAndConversationId(8L,3L)).thenReturn(Optional.of(old));
        assertThat(store.recallMessage(3L,1L,ctx(),8L,0)).contains("nextOffset=300").doesNotContain("�");
        assertThat(store.recallMessage(3L,1L,ctx(),8L,300)).endsWith("😀").contains("END");
        assertThatThrownBy(()->store.recallMessage(3L,1L,ctx(),99L,0)).isInstanceOf(BizException.class);
        assertThatThrownBy(()->store.recallMessage(3L,1L,ctx(),8L,302)).isInstanceOf(BizException.class);
    }

    @Test void specialistOmitsUnrelatedAssistantAnswersAndRetainsUserCorrections() {
        var store=mock(ConversationStore.class); var gate=mock(TaskGate.class);
        var user=message(1,"USER","改为周六"); var answer=message(2,"ASSISTANT","其他专家的完整草稿");
        when(store.context(3L,6)).thenReturn(List.of(user,answer));
        when(store.contextText(eq(user),any())).thenReturn(user.getContent());
        when(store.taskState(3L,1L)).thenReturn("taskId=4 status=SUCCESS");
        var actual=new ContextAssembler(store,new AgentProperties(),gate).specialist("角色",3L,"只核对排班",ctx());
        assertThat(actual).anyMatch(m->"改为周六".equals(m.content()));
        assertThat(actual).noneMatch(m->"其他专家的完整草稿".equals(m.content()));
        assertThat(actual.getLast().content()).isEqualTo("只核对排班");
        verify(store,never()).contextText(eq(answer),any());
    }
}
