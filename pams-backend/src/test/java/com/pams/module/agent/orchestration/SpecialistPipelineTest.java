package com.pams.module.agent.orchestration;

import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.service.TaskGate;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SpecialistPipelineTest {
    @Autowired SpecialistAgent specialist;
    @Autowired TaskGate gate;
    @Autowired AgentEventPublisher events;
    @Autowired ActivityRepository activities;
    @Autowired AgentRunService runs;
    ToolContext ctx;
    @BeforeEach void context() {
        OrgContext.set(1L);
        LoginUser user=new LoginUser(1L,"teacher","测试", "TEACHER",5,null,null,1L);
        var perms=List.of(new SimpleGrantedAuthority("activity:create"),new SimpleGrantedAuthority("activity:view"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,perms));
        ctx=new ToolContext(user,perms,null,events.newStream("pipeline",1L));
    }
    @AfterEach void clear() { OrgContext.clear(); SecurityContextHolder.clearContext(); }
    SpecialistAgent.Result invoke(Map<String,Object> args) {
        return specialist.execute(AgentCatalog.GENERALIST,"创建活动",ctx,
            List.of(new LlmProvider.ToolCallRequest("c","createActivity",args)),ctx.getStreamId(),"teacher");
    }
    @Test void specialistPreviewRequiresConfirmationAndRepeatedConfirmHasNoSecondWrite() {
        long before=activities.count();
        var preview=invoke(Map.of("name","协作闭环验收","startDate","2026-11-01"));
        assertThat(preview.state()).isEqualTo("WAITING_CONFIRM");
        assertThat(preview.plan()).isNotNull();
        assertThat(preview.toolCalls()).hasSize(1);
        assertThat(activities.count()).isEqualTo(before);
        var result=gate.confirm(preview.plan().planId(),preview.plan().confirmToken(),ctx);
        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(activities.count()).isEqualTo(before+1);
        gate.confirm(preview.plan().planId(),preview.plan().confirmToken(),ctx);
        assertThat(activities.count()).isEqualTo(before+1);
    }
    @Test void specialistFormCanBeSubmittedAndTurnedIntoAnExecutablePlan() {
        var missing=invoke(Map.of());
        assertThat(missing.state()).isEqualTo("WAITING_INPUT");
        assertThat(missing.form()).containsKey("taskId");
        var result=gate.submitInput(((Number)missing.form().get("taskId")).longValue(),
            Map.of("name","补参闭环验收","startDate","2026-11-02"),ctx);
        assertThat(result.get("status")).isEqualTo("PLAN");
        @SuppressWarnings("unchecked") Map<String,Object> plan=(Map<String,Object>)result.get("plan");
        assertThat(gate.confirm(((Number)plan.get("planId")).longValue(),(String)plan.get("confirmToken"),ctx).get("status")).isEqualTo("SUCCESS");
    }
    @Test void readonlySpecialistCannotCreateAnAction() {
        var result=specialist.execute(AgentCatalog.GENERALIST,"创建活动",ctx.withMode("readonly"),
            List.of(new LlmProvider.ToolCallRequest("c","createActivity",Map.of())),ctx.getStreamId(),"teacher");
        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.plan()).isNull(); assertThat(result.form()).isNull();
    }
    @Test void confirmedActionSettlesItsRunWithoutClaimingQueuedWorkIsFinished() {
        var run=runs.startRun(null,1L,"创建活动并准备文稿","P1");
        ctx=ctx.withRunId(run.getId());
        var result=invoke(Map.of("name","多步骤确认验收","startDate","2026-11-03"));
        var bb=new Blackboard();
        var create=new Blackboard.PlanItem(); create.setAgent("planning"); create.setStatus("WAITING_CONFIRM");
        var writing=new Blackboard.PlanItem(); writing.setAgent("writing");
        bb.getPlan().addAll(List.of(create,writing));
        runs.finishRun(run.getId(),"WAITING_CONFIRM",bb);
        var receipt=gate.confirm(result.plan().planId(),result.plan().confirmToken(),ctx);
        assertThat(receipt.get("status")).isEqualTo("SUCCESS");
        assertThat(receipt.get("executionState")).isEqualTo("PARTIAL");
        assertThat(runs.getOwned(run.getId(),1L).getStatus()).isEqualTo("PARTIAL");
    }
}
