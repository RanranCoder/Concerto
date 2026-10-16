package com.pams.module.agent.service;

import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.entity.AgentTask;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationStatusTest {
    AgentMessage reply() { var m=new AgentMessage();m.setRole(AgentMessage.ROLE_ASSISTANT);m.setCreatedAt(LocalDateTime.of(2026,10,14,10,0));return m; }
    AgentTask task(String status) {var t=new AgentTask();t.setStatus(status);t.setCreatedAt(LocalDateTime.of(2026,10,14,9,59));t.setFinishedAt(LocalDateTime.of(2026,10,14,10,1));return t;}
    @Test void pendingAndInputStatusesWinOverPreviouslyCompletedMessages() {
        assertThat(ConversationStore.conversationStatus(List.of(task(AgentTask.PENDING_CONFIRM)),reply())).isEqualTo("PENDING_CONFIRM");
        assertThat(ConversationStore.conversationStatus(List.of(task(AgentTask.WAITING_INPUT)),reply())).isEqualTo("WAITING_INPUT");
    }
    @Test void failedOrPartialWritesAreNotCompleted() {
        assertThat(ConversationStore.conversationStatus(List.of(task(AgentTask.FAILED)),reply())).isEqualTo("FAILED");
        assertThat(ConversationStore.conversationStatus(List.of(task(AgentTask.PARTIAL)),reply())).isEqualTo("PARTIAL");
    }
    @Test void aNewerReadOnlyReplyCanCompleteAnEarlierFailedTaskConversation() {
        var m=reply();m.setCreatedAt(LocalDateTime.of(2026,10,14,10,5));
        assertThat(ConversationStore.conversationStatus(List.of(task(AgentTask.FAILED)),m)).isEqualTo("COMPLETED");
    }
    @Test void noReplyIsReadyInsteadOfClaimingCompletion() {
        assertThat(ConversationStore.conversationStatus(List.of(),null)).isEqualTo("READY");
        var m=reply();m.setRole(AgentMessage.ROLE_USER);
        assertThat(ConversationStore.conversationStatus(List.of(),m)).isEqualTo("READY");
    }
}
