package com.pams.module.agent.service;

import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolRegistry;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TaskGateAuditTest {
    AgentTaskRepository repository;
    ToolRegistry registry;
    TaskGate gate;
    AgentTask task;
    ToolContext context(String mode) {
        return new ToolContext(new LoginUser(1L,"reader","读者","DIRECTOR",4,null,null,1L),
                List.of(), 1L, "stream", mode);
    }
    @BeforeEach void setup() {
        repository = mock(AgentTaskRepository.class);
        registry = mock(ToolRegistry.class);
        gate = new TaskGate(repository, registry, mock(AuditService.class), mock(AgentEventPublisher.class));
        task = new AgentTask(); task.setId(1L); task.setUserId(1L);
        task.setToolName("write"); task.setConfirmToken("token");
        task.setStatus(AgentTask.PENDING_CONFIRM); task.setCreatedAt(LocalDateTime.now());
        when(repository.findByIdAndUserId(1L,1L)).thenReturn(Optional.of(task));
    }
    @Test void expiredPlansNeverClaimOrCommit() {
        task.setCreatedAt(LocalDateTime.now().minusHours(25));
        assertThatThrownBy(() -> gate.confirm(1L,"token",context("standard"))).hasMessageContaining("过期");
        verify(repository, never()).claimForRun(any(),any(),any(),any());
    }
    @Test void removedToolCannotLeaveTheTaskRunning() {
        when(registry.find("write")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> gate.confirm(1L,"token",context("standard"))).hasMessageContaining("下线");
        verify(repository, never()).claimForRun(any(),any(),any(),any());
    }
    @Test void readonlyAttemptDoesNotConsumeThePlan() {
        AgentTool tool = mock(AgentTool.class); when(tool.writeOperation()).thenReturn(true);
        when(registry.find("write")).thenReturn(Optional.of(tool));
        assertThatThrownBy(() -> gate.confirm(1L,"token",context("readonly"))).hasMessageContaining("模式");
        verify(repository, never()).claimForRun(any(),any(),any(),any());
        verify(repository, never()).finish(any(),any(),any(),any());
    }
    @Test void cancellationCannotOverwriteAConcurrentClaim() {
        when(repository.cancelPending(eq(1L),eq(1L),any())).thenReturn(0);
        assertThatThrownBy(() -> gate.cancel(1L,1L)).hasMessageContaining("状态已变化");
        verify(repository, never()).save(any());
    }
    @Test void pendingTaskCancellationUsesAConditionalUpdate() {
        when(repository.cancelPending(eq(1L),eq(1L),any())).thenReturn(1);
        gate.cancel(1L,1L);
        verify(repository).cancelPending(eq(1L),eq(1L),any());
        verify(repository, never()).save(any());
    }
}
