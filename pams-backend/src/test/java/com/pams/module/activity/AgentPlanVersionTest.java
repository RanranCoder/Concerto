package com.pams.module.activity;

import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.*;
import com.pams.module.agent.core.ToolContext;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AgentPlanVersionTest {
    @Test void confirmationCreatesNewDraftAndPreservesOriginalAndRejectsStalePreview() {
        var plans=mock(ActivityPlanRepository.class);
        var activities=mock(ActivityRepository.class);
        var access=mock(PlanAccessService.class);
        var attachments=mock(PlanAttachmentService.class);
        var service=new PlanService(plans,activities,mock(org.springframework.context.ApplicationEventPublisher.class),access,attachments);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"entityManager",mock(jakarta.persistence.EntityManager.class));
        var ctx=mock(ToolContext.class);
        var user=mock(LoginUser.class);
        when(ctx.getLoginUser()).thenReturn(user);when(user.getOrgId()).thenReturn(1L);
        when(ctx.getUserId()).thenReturn(2L);when(ctx.hasPermission("plan:edit")).thenReturn(true);
        var activity=new Activity();activity.setId(5L);activity.setOrgId(1L);
        when(activities.lockById(5L)).thenReturn(Optional.of(activity));
        var original=new ActivityPlan();original.setId(8L);original.setVersion(1);original.setStatus(ActivityPlan.PlanStatus.DRAFT);original.setContent("原稿");
        when(plans.findTopByActivityIdOrderByVersionDesc(5L)).thenReturn(Optional.of(original));
        when(plans.save(any())).thenAnswer(call->call.getArgument(0));
        var request=new PlanRequest();request.setActivityId(5L);request.setContent("生成的新稿");
        var saved=service.createAgentVersion(request,ctx,8L,original.getRevision());
        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getContent()).isEqualTo("生成的新稿");
        assertThat(original.getContent()).isEqualTo("原稿");
        assertThatThrownBy(()->service.createAgentVersion(request,ctx,7L,original.getRevision())).hasMessageContaining("已变化");
        verify(plans,times(1)).save(any());
    }
}
