package com.pams.module.agent.service;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.*;
import com.pams.module.agent.core.*;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentPlanReadServiceTest {
    ActivityRepository activities=mock(ActivityRepository.class);
    ActivityPlanRepository plans=mock(ActivityPlanRepository.class);
    PlanAccessService access=mock(PlanAccessService.class);
    PlanAttachmentService attachments=mock(PlanAttachmentService.class);
    AgentPlanReadService reads=new AgentPlanReadService(activities,plans,access,attachments);
    ToolContext ctx=mock(ToolContext.class);LoginUser user=mock(LoginUser.class);
    ActivityPlan plan=new ActivityPlan();Activity activity=new Activity();
    @BeforeEach void setup() {
        when(ctx.getLoginUser()).thenReturn(user);when(user.getOrgId()).thenReturn(1L);when(ctx.getUserId()).thenReturn(8L);
        when(ctx.hasPermission(anyString())).thenReturn(true);
        activity.setId(2L);activity.setOrgId(1L);activity.setName("活动");when(activities.findById(2L)).thenReturn(Optional.of(activity));
        plan.setId(3L);plan.setActivityId(2L);plan.setVersion(1);plan.setRevision(0L);plan.setOrgId(1L);
        when(plans.findById(3L)).thenReturn(Optional.of(plan));when(access.canRead(plan,ctx)).thenReturn(true);
    }
    @Test void lowPermissionAndCrossOrganizationReadsAreRejectedBeforeAttachmentAccess() {
        when(ctx.hasPermission("plan:view")).thenReturn(false);
        assertThat(reads.document(ctx,3L,null,0).payload()).containsEntry("errorCategory","NOT_FOUND_OR_NOT_VISIBLE");
        verifyNoInteractions(attachments);
        when(ctx.hasPermission("plan:view")).thenReturn(true);when(access.canRead(plan,ctx)).thenReturn(false);
        assertThat(reads.document(ctx,3L,null,0).isOk()).isFalse();verifyNoInteractions(attachments);
        activity.setOrgId(9L);
        assertThat(reads.detail(ctx,2L).isOk()).isFalse();
    }
    @Test void versionPaginationFiltersHiddenEntriesBeforeCountingAndNeverExposesThem() {
        var hidden=new ActivityPlan();hidden.setId(99L);hidden.setVersion(99);
        when(plans.findByActivityIdOrderByVersionDesc(2L)).thenReturn(List.of(hidden,plan));
        var result=reads.versions(ctx,2L,0);
        assertThat(result.isOk()).isTrue();assertThat(result.payload()).containsEntry("total",1).containsEntry("hasMore",false);
        assertThat(result.payload().get("versions").toString()).doesNotContain("99");
    }
    @Test void nativePagesRequireMatchingBaselineAndNeverBecomeRevisionEligible() throws Exception {
        plan.setDocumentFileId(7L);
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<6;i++) doc.createParagraph().createRun().setText("段落"+i);
            doc.write(out);when(attachments.documentResource(plan)).thenReturn(new ByteArrayResource(out.toByteArray()));
        }
        var page=reads.document(ctx,3L,null,0);assertThat(page.isOk()).isTrue();
        assertThat(page.payload()).containsEntry("nextCursor",4).containsEntry("revisionEligible",false);
        var tool=new com.pams.module.agent.tool.ReadPlanDocumentTool(reads);
        String modelView=tool.toLlmText(ctx,page);
        assertThat(modelView).contains("段落0","段落3","baseline","nextCursor").doesNotContain("段落4");
        String baseline=(String)page.payload().get("baseline");
        assertThat(reads.document(ctx,3L,null,4).payload()).containsEntry("errorCategory","VALIDATION_ERROR");
        assertThat(reads.document(ctx,3L,baseline,4).payload()).containsEntry("nextCursor",null);
        plan.setRevision(1L);
        assertThat(reads.document(ctx,3L,baseline,4).payload()).containsEntry("errorCategory","STALE_BASELINE");
        verify(plans,never()).save(any());
    }
    @Test void legacyFieldsAreExplicitAndContentChangeInvalidatesBaselineEvenWithoutRevisionChange() {
        plan.setBudget("<p>2500元</p>");
        var first=reads.document(ctx,3L,null,0);String baseline=first.payload().get("baseline").toString();
        assertThat(first.payload().get("blocks").toString()).contains("2500元","LEGACY_HTML");
        assertThat(first.payload().get("unsupportedElements").toString()).contains("NOT_NATIVE_DOCX");
        plan.setBudget("<p>3000元</p>");
        assertThat(reads.document(ctx,3L,baseline,0).payload()).containsEntry("errorCategory","STALE_BASELINE");
    }
}
