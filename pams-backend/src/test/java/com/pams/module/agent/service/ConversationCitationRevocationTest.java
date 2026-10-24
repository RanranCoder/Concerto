package com.pams.module.agent.service;

import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.entity.AgentConversation;
import com.pams.module.agent.repository.AgentConversationRepository;
import com.pams.module.agent.repository.AgentMessageRepository;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.knowledge.service.PermissionResolver;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationCitationRevocationTest {
    @Test void historyHidesDerivedTextAndMetadataWhenAReferenceIsRevoked() {
        var permissions = mock(PermissionResolver.class);
        var conversations = mock(AgentConversationRepository.class);
        var messages = mock(AgentMessageRepository.class);
        var store = new ConversationStore(conversations,messages,mock(AgentTaskRepository.class),new PolicyGuard(),permissions);
        var conversation = new AgentConversation(); conversation.setId(3L); conversation.setUserId(1L);
        when(conversations.findByIdAndUserIdAndDeleted(3L,1L,0)).thenReturn(Optional.of(conversation));
        var message = new AgentMessage(); message.setRole(AgentMessage.ROLE_ASSISTANT);
        message.setContent("历史资料正文"); message.setThinkingJson("[{\"text\":\"秘密推理\"}]");
        message.setCitationsJson("[{\"index\":3,\"sourceType\":\"activity_plan\",\"sourceId\":9,\"snippet\":\"秘密\",\"sourceTitle\":\"敏感标题\"}]");
        when(messages.findByConversationIdOrderByIdDesc(eq(3L),any())).thenReturn(new PageImpl<>(List.of(message)));
        var view = store.messages(3L,1L,1,50,null).getRecords().getFirst();
        assertThat(view.get("content")).asString().doesNotContain("历史资料正文").contains("已不可见");
        assertThat((List<?>)view.get("thinking")).isEmpty();
        var citation = (Map<?,?>)((List<?>)view.get("citations")).getFirst();
        assertThat(citation.get("snippet")).isEqualTo("");
        assertThat(citation.get("sourceTitle")).isEqualTo("已不可见的依据");
        assertThat(citation.get("revoked")).isEqualTo(true);
    }
    @Test void revokedSourcesAreNotReintroducedIntoModelContext() {
        var permissions = mock(PermissionResolver.class);
        var store = new ConversationStore(mock(AgentConversationRepository.class), mock(AgentMessageRepository.class),
                mock(AgentTaskRepository.class), new PolicyGuard(), permissions);
        var message = new AgentMessage();
        message.setContent("不应继续可见的历史资料正文");
        message.setCitationsJson("[{\"sourceType\":\"activity_plan\",\"sourceId\":9,\"snippet\":\"秘密\",\"sourceTitle\":\"敏感标题\"}]");
        when(permissions.canAccess(eq("activity_plan"),eq(9L),isNull())).thenReturn(false);
        assertThat(store.contextText(message,null)).doesNotContain("历史资料正文").contains("已不可见");
        assertThat(message.getContent()).isEqualTo("不应继续可见的历史资料正文");
        when(permissions.canAccess(eq("activity_plan"),eq(9L),isNull())).thenReturn(true);
        assertThat(store.contextText(message,null)).isEqualTo(message.getContent());
    }
}
