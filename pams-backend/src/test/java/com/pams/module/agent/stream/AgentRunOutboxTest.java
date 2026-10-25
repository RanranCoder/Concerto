package com.pams.module.agent.stream;

import com.pams.common.OrgContext;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.orchestration.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AgentRunOutboxTest {
    @Test void brokerFailureIsNotAcknowledgedAndRetryDeliversMetadataOnly() {
        var events=mock(AgentRunEventRepository.class);var state=mock(WorkItemState.class);var publisher=mock(AgentEventPublisher.class);
        var authorization=mock(TenantAuthorizationService.class);var scheduler=mock(DagScheduler.class);
        var run=new AgentRun();run.setId(1L);run.setOrgId(1L);run.setUserId(1L);
        var event=new AgentRunEvent();event.setId(7L);event.setRunId(1L);event.setOrgId(1L);event.setUserId(1L);event.setEventSeq(9);event.setStatus("WAITING_CONFIRM");
        when(events.pending(any(),any())).thenReturn(List.of(event));when(events.claim(eq(7L),any(),any(),any())).thenReturn(1);
        when(state.owned(1L,1L)).thenReturn(run);when(state.authority(run)).thenReturn(Set.of());when(authorization.valid(1L)).thenReturn(true);
        when(authorization.identity(1L)).thenReturn(Map.of("org_id",1L));when(authorization.authorities(1L)).thenReturn(Set.of());
        when(authorization.refresh(any())).thenReturn(new ToolContext(new LoginUser(1L,"teacher","","DIRECTOR",4,null,null,1L),List.of(),null,null));
        doThrow(new IllegalStateException("broker unavailable")).doNothing().when(publisher).publishDurable(anyString(),anyMap());
        var outbox=new AgentRunOutbox(events,mock(AgentRunRepository.class),mock(AgentTaskRepository.class),state,scheduler,publisher,authorization,new AgentProperties(),mock(Environment.class));
        try {
            OrgContext.set(99L);outbox.deliverOnce();verify(events,never()).delivered(anyLong(),anyString(),any());
            outbox.deliverOnce();verify(events,times(1)).delivered(eq(7L),anyString(),any());
            assertThat(OrgContext.get()).isEqualTo(99L);
            @SuppressWarnings("unchecked") var payload=org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(publisher,times(2)).publishDurable(eq("teacher"),payload.capture());
            assertThat(payload.getValue()).containsKey("eventSeq").doesNotContainKeys("text","preview","quote","reasoning");
            verifyNoInteractions(scheduler);
        } finally {OrgContext.clear();}
    }
}
