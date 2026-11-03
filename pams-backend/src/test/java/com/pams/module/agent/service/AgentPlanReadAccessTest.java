package com.pams.module.agent.service;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.*;
import com.pams.module.agent.core.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.repository.UserRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentPlanReadAccessTest {
    @Test void actualVersionPolicyRejectsCurrentNonOwnerAndCrossTenantAndRechecksEveryPage() {
        var plans=mock(ActivityPlanRepository.class);var activities=mock(ActivityRepository.class);
        var access=new PlanAccessService(plans,activities,mock(UserRepository.class),mock(TenantAuthorizationService.class));
        var attachments=mock(PlanAttachmentService.class);
        var reads=new AgentPlanReadService(activities,plans,access,attachments);
        var ctx=mock(ToolContext.class);var user=mock(LoginUser.class);
        when(ctx.getLoginUser()).thenReturn(user);when(user.getOrgId()).thenReturn(1L);when(ctx.getUserId()).thenReturn(8L);
        when(ctx.hasPermission(anyString())).thenReturn(true);
        var a=new Activity();a.setId(2L);a.setOrgId(1L);when(activities.findById(2L)).thenReturn(Optional.of(a));
        var p=new ActivityPlan();p.setId(3L);p.setActivityId(2L);p.setOrgId(1L);p.setOwnerId(9L);p.setBudget("原文");
        when(plans.findById(3L)).thenReturn(Optional.of(p));when(plans.findTopByActivityIdOrderByVersionDesc(2L)).thenReturn(Optional.of(p));
        when(plans.findByActivityIdOrderByVersionDesc(2L)).thenReturn(List.of(p));
        assertThat(reads.document(ctx,3L,null,0).isOk()).isFalse();
        assertThat(reads.versions(ctx,2L,0).payload()).containsEntry("total",0);
        p.setOwnerId(8L);
        var allowed=reads.document(ctx,3L,null,0);assertThat(allowed.isOk()).isTrue();
        when(user.getOrgId()).thenReturn(99L);
        assertThat(reads.document(ctx,3L,allowed.payload().get("baseline").toString(),0).isOk()).isFalse();
        when(user.getOrgId()).thenReturn(1L);when(ctx.hasPermission("plan:view")).thenReturn(false);
        assertThat(reads.document(ctx,3L,null,0).isOk()).isFalse();
        verifyNoInteractions(attachments);verify(plans,never()).save(any());
    }
}
