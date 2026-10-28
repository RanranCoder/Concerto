package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.PlanAccessService;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.knowledge.source.ActivityPlanSource;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.repository.UserRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PlanAccessServiceTest {
    ActivityPlanRepository plans=mock(ActivityPlanRepository.class);
    ActivityRepository activities=mock(ActivityRepository.class);
    PlanAccessService access=new PlanAccessService(plans,activities,mock(UserRepository.class),mock(TenantAuthorizationService.class));
    ActivityPlan current=new ActivityPlan(),history=new ActivityPlan();
    ToolContext ctx(long uid,long org) {
        return new ToolContext(new LoginUser(uid,"user","名字","TEACHER",6,null,null,org),
                List.of(new SimpleGrantedAuthority("activity:view"),new SimpleGrantedAuthority("plan:view"),new SimpleGrantedAuthority("plan:edit")),null,null);
    }
    @BeforeEach void setup() {
        current.setId(2L); current.setActivityId(1L); current.setOrgId(1L); current.setOwnerId(10L);
        history.setId(1L); history.setActivityId(1L); history.setOrgId(1L); history.setOwnerId(10L);
        var reviewer=new PlanReviewer(); reviewer.setUserId(20L); current.getReviewers().add(reviewer);
        when(plans.findTopByActivityIdOrderByVersionDesc(1L)).thenReturn(Optional.of(current));
        when(plans.findById(2L)).thenReturn(Optional.of(current));
        when(plans.findById(1L)).thenReturn(Optional.of(history));
    }
    @Test void sameOrgAuthorAndSelectedCanReadCurrentButOtherLeadersCannot() {
        assertThat(access.canRead(current,ctx(10,1))).isTrue();
        assertThat(access.canRead(current,ctx(20,1))).isTrue();
        assertThat(access.canRead(current,ctx(30,1))).isFalse();
        assertThat(access.canRead(history,ctx(30,1))).isTrue();
    }
    @Test void crossTenantAndAnonymousCannotReadEitherVersion() {
        for(var p:List.of(current,history)) {
            assertThat(access.canRead(p,ctx(10,2))).isFalse();
            assertThat(access.canRead(p,null)).isFalse();
        }
    }
    @Test void knowledgeRetrievalUsesSameVersionPolicy() {
        var source=new ActivityPlanSource(plans,activities,access);
        assertThat(source.canAccess(ctx(30,1),2L)).isFalse();
        assertThat(source.canAccess(ctx(20,1),2L)).isTrue();
        assertThat(source.canAccess(ctx(30,1),1L)).isTrue();
        assertThat(source.canAccess(ctx(30,2),1L)).isFalse();
    }
    @Test void attachmentsFollowEveryReferencingVersionAndUnboundUploadsAreDistinct() {
        when(plans.referencingFile("%/api/files/7/%")).thenReturn(List.of(current));
        assertThat(access.canReadAttachment(7L,ctx(30,1))).isFalse();
        assertThat(access.canReadAttachment(7L,ctx(20,1))).isTrue();
        when(plans.referencingFile("%/api/files/7/%")).thenReturn(List.of(current,history));
        assertThat(access.canReadAttachment(7L,ctx(30,1))).isTrue();
        assertThat(access.canReadAttachment(8L,ctx(30,1))).isNull();
    }
    @Test void historyAndSelectedReviewersCannotMutateCurrent() {
        assertThatThrownBy(() -> access.requireOwner(history,ctx(10,1))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> access.requireOwner(current,ctx(20,1))).isInstanceOf(BizException.class);
    }
    @Test void nativeDocxAttachmentUsesTheSameVisibilityAsItsPlanVersion() {
        when(plans.findByDocumentFileId(50L)).thenReturn(List.of(current));
        assertThat(access.canReadAttachment(50L,ctx(30,1))).isFalse();
        assertThat(access.canReadAttachment(50L,ctx(20,1))).isTrue();
        when(plans.findByDocumentFileId(50L)).thenReturn(List.of(history));
        assertThat(access.canReadAttachment(50L,ctx(30,1))).isTrue();
    }
    @Test void nativeDocumentSearchUsesTheFullDerivedTextInsteadOfTheLegacyIndex() {
        current.setDocumentFileId(50L);
        current.setDocumentText("完整正文".repeat(3000)+"文末校验");
        current.setContent("截断的兼容索引");
        var source=new ActivityPlanSource(plans,activities,access);
        assertThat(source.extractOne(2L).toString()).contains("文末校验").doesNotContain("截断的兼容索引");
    }
}
