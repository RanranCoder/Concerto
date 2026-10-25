package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.service.*;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real H2, real tool previews/commits, scripted model. No paid model or business writes outside test data. */
@SpringBootTest @ActiveProfiles("test")
class DagSchedulerTest {
    @Autowired DagScheduler scheduler;
    @Autowired WorkItemState state;
    @Autowired AgentRunService runService;
    @Autowired AgentTaskRepository tasks;
    @Autowired AgentWorkItemRepository items;
    @Autowired AgentRunRepository runs;
    @Autowired AgentStepRepository steps;
    @Autowired TaskGate gate;
    @Autowired TenantAuthorizationService auth;
    @Autowired AgentEventPublisher publisher;
    @Autowired ActivityRepository activities;
    @Autowired JdbcTemplate db;
    @Autowired AgentRequestService requests;
    @Autowired AgentRunEventRepository eventRows;
    @Autowired AgentReviewIssueRepository issues;
    @Autowired com.pams.module.agent.stream.AgentRunOutbox outbox;
    @Autowired org.springframework.transaction.PlatformTransactionManager tx;
    @Autowired ToolRegistry toolRegistry;
    @Autowired AgentOrchestrator orchestrator;
    @Autowired AgentMessageRepository messageRows;

    @MockitoBean AgentLlmCaller caller;
    @MockitoBean(name="agentExecutor") Executor executor;
    final Queue<Runnable> queue=new ConcurrentLinkedQueue<>();
    final List<Long> runIds=new ArrayList<>();
    final ObjectMapper json=new ObjectMapper();
    ToolContext ctx;
    String prefix;
    @BeforeEach void setup() {
        OrgContext.set(1L); prefix="s2-"+UUID.randomUUID().toString().substring(0,8);
        var user=new LoginUser(1L,"teacher","测试","DIRECTOR",4,null,null,1L);
        var permissions=auth.authorities(1L).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,permissions));
        ctx=new ToolContext(user,permissions,null,publisher.newStream("s2",1L));
        doAnswer(i->{ queue.add(i.getArgument(0)); return null; }).when(executor).execute(any());
    }
    @AfterEach void cleanup() {
        queue.clear();
        for(Long id:runIds) {
            db.update("delete from agent_review_issue where run_id=?",id);
            db.update("delete from agent_run_event where run_id=?",id);
            db.update("delete from agent_request where run_id=?",id);
            db.update("delete from agent_step where run_id=?",id);
            db.update("delete from agent_task where run_id=?",id);
            db.update("delete from agent_work_item where run_id=?",id);
            db.update("delete from agent_run where id=?",id);
        }
        db.update("delete from article where activity_id in (select id from activity where name like ?)",prefix+"%");
        db.update("delete from activity where name like ?",prefix+"%");
        OrgContext.clear(); SecurityContextHolder.clearContext();
    }
    Blackboard.PlanItem node(int key,String instruction,Integer... dependencies) {
        var n=new Blackboard.PlanItem(); n.setId(key); n.setAgent("generalist"); n.setTask(instruction);
        n.setDependsOn(new ArrayList<>(List.of(dependencies))); return n;
    }
    AgentRun initialize(Blackboard.PlanItem... plan) {
        var run=runService.startRun(null,1L,"S2验收","P2"); runIds.add(run.getId());
        var bb=new Blackboard(); bb.setPlan(List.of(plan));
        state.initialize(run.getId(),bb,ctx.withRunId(run.getId())); return run;
    }
    LlmProvider.ChatResponse create(String suffix) {
        return LlmProvider.ChatResponse.calling(List.of(new LlmProvider.ToolCallRequest("create","createActivity",
            Map.of("name",prefix+suffix,"startDate","2026-11-20"))));
    }
    void drain() { int limit=20; while(!queue.isEmpty() && limit-->0) queue.remove().run(); assertThat(queue).isEmpty(); }
    @Test void afterCommitResumesDownstreamUsingTheCommittedActivityId() throws Exception {
        var run=initialize(node(1,"create-first"),node(2,"write-after",1));
        var prompts=new ArrayList<String>();
        when(caller.callIsolated(anyList(),anyList(),any())).thenAnswer(i->{
            List<LlmProvider.ChatMessage> messages=i.getArgument(0); String prompt=messages.get(1).content();
            if(messages.getFirst().content().contains("独立内容审查员")) return LlmProvider.ChatResponse.text("{\"issues\":[]}","test");
            prompts.add(prompt);
            if(prompt.startsWith("create-first")) return create("-chain");
            var first=tasks.findByRunIdOrderByIdAsc(run.getId()).getFirst();
            var committed=AgentJson.readTree(json,first.getResultJson());
            long activityId=committed.path("references").get(0).path("id").asLong();
            assertThat(prompt).contains("\"id\":"+activityId);
            return LlmProvider.ChatResponse.calling(List.of(new LlmProvider.ToolCallRequest("draft","draftArticleFromActivity",Map.of("activityId",activityId))));
        });
        assertThat(scheduler.drive(run.getId(),1L).executionState()).isEqualTo("WAITING_CONFIRM");
        assertThat(prompts).hasSize(1);
        var first=tasks.findByRunIdOrderByIdAsc(run.getId()).getFirst();
        long before=activities.count();
        gate.confirm(first.getId(),first.getConfirmToken(),ctx);
        assertThat(queue).hasSize(1); // callback is dispatched after node transaction committed
        assertThat(activities.count()).isEqualTo(before+1);
        gate.confirm(first.getId(),first.getConfirmToken(),ctx);
        assertThat(activities.count()).isEqualTo(before+1);
        drain();
        assertThat(tasks.findByRunIdOrderByIdAsc(run.getId())).extracting(AgentTask::getStatus).containsExactly("SUCCESS","PENDING_CONFIRM");
        assertThat(state.nodes(run.getId())).extracting(AgentWorkItem::getStatus).containsExactly("SUCCESS","WAITING_CONFIRM");
        assertThat(prompts).hasSize(2);
        assertThat(scheduler.view(run.getId(),ctx).get("actions")).asList().hasSize(2);
    }
    @Test void independentConfirmationsCoexistAndCanFinishInEitherOrder() {
        var run=initialize(node(1,"left"),node(2,"right"));
        var started=new java.util.concurrent.CountDownLatch(2);
        when(caller.callIsolated(anyList(),anyList(),any())).thenAnswer(i->{
            started.countDown(); assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            List<LlmProvider.ChatMessage> m=i.getArgument(0); return create(m.get(1).content().startsWith("left")?"-left":"-right");
        });
        scheduler.drive(run.getId(),1L);
        var actions=tasks.findByRunIdOrderByIdAsc(run.getId());
        assertThat(actions).hasSize(2).allSatisfy(a->assertThat(a.getStatus()).isEqualTo("PENDING_CONFIRM"));
        long before=activities.count();
        gate.confirm(actions.get(1).getId(),actions.get(1).getConfirmToken(),ctx); drain();
        assertThat(tasks.findById(actions.getFirst().getId()).orElseThrow().getStatus()).isEqualTo("PENDING_CONFIRM");
        gate.confirm(actions.getFirst().getId(),actions.getFirst().getConfirmToken(),ctx); drain();
        assertThat(activities.count()).isEqualTo(before+2);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("SUCCESS");
        assertThatThrownBy(()->state.retry(run.getId(),state.nodes(run.getId()).getFirst().getId(),1L)).isInstanceOf(BizException.class);
        verify(caller,times(2)).callIsolated(anyList(),anyList(),any());
    }
    @Test void retryKeepsSuccessfulAncestorsAndPreservesAttemptHistory() {
        var run=initialize(node(1,"evidence"),node(2,"unstable",1),node(3,"last",2));
        var attempts=new AtomicInteger();
        when(caller.callIsolated(anyList(),anyList(),any())).thenAnswer(i->{
            List<LlmProvider.ChatMessage> m=i.getArgument(0);
            if(m.get(1).content().startsWith("unstable") && attempts.getAndIncrement()==0) throw new IllegalStateException("offline");
            return LlmProvider.ChatResponse.text("已核对的事实","test");
        });
        scheduler.drive(run.getId(),1L);
        var nodes=state.nodes(run.getId());
        assertThat(nodes).extracting(AgentWorkItem::getStatus).containsExactly("SUCCESS","FAILED","QUEUED");
        state.retry(run.getId(),nodes.get(1).getId(),1L); scheduler.drive(run.getId(),1L);
        assertThat(state.nodes(run.getId())).extracting(AgentWorkItem::getAttempt).containsExactly(1,2,1);
        assertThat(steps.findByRunIdOrderBySeqAsc(run.getId())).extracting(AgentStep::getStatus).containsExactly("SUCCESS","FAILED","SUCCESS","SUCCESS");
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("SUCCESS");
    }
    @Test void revisionCancelsOnlyAffectedTokensAndRejectsCommittedBranches() {
        var run=initialize(node(1,"left"),node(2,"dependent",1),node(3,"sibling"));
        when(caller.callIsolated(anyList(),anyList(),any())).thenAnswer(i->create("-"+UUID.randomUUID().toString().substring(0,4)));
        scheduler.drive(run.getId(),1L);
        var nodes=state.nodes(run.getId()); var actions=tasks.findByRunIdOrderByIdAsc(run.getId());
        var rootAction=actions.stream().filter(a->a.getWorkItemId().equals(nodes.getFirst().getId())).findFirst().orElseThrow();
        var siblingAction=actions.stream().filter(a->a.getWorkItemId().equals(nodes.getLast().getId())).findFirst().orElseThrow();
        state.revise(run.getId(),nodes.getFirst().getId(),1L,1,"revised-left");
        assertThat(state.nodes(run.getId())).extracting(AgentWorkItem::getRevision).containsExactly(2,2,1);
        assertThat(tasks.findById(rootAction.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(tasks.findById(siblingAction.getId()).orElseThrow().getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(gate.confirm(rootAction.getId(),rootAction.getConfirmToken(),ctx).get("status")).isEqualTo("CANCELLED");
        assertThatThrownBy(()->state.revise(run.getId(),nodes.getFirst().getId(),1L,1,"stale")).isInstanceOf(BizException.class);
        gate.confirm(siblingAction.getId(),siblingAction.getConfirmToken(),ctx); drain();
        assertThatThrownBy(()->state.revise(run.getId(),nodes.getLast().getId(),1L,1,"change committed")).isInstanceOf(BizException.class);
    }
    @Test void dependencyValidationRejectsCyclesAndUnknownNodes() {
        assertThatThrownBy(()->WorkItemState.validate(List.of(node(1,"a",2),node(2,"b",1)))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->WorkItemState.validate(List.of(node(1,"a",42)))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->WorkItemState.validate(List.of(node(1,"a"),node(1,"b")))).isInstanceOf(BizException.class);
    }
    @Test void runLeasePreventsDuplicateDriversAndOwnershipCannotBeBypassed() {
        var run=initialize(node(1,"text"));
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(LlmProvider.ChatResponse.text("完成","test"));
        assertThat(state.acquire(run.getId(),"held")).isTrue();
        assertThat(state.acquire(run.getId(),"other")).isFalse(); scheduler.drive(run.getId(),1L);
        verifyNoInteractions(caller); state.release(run.getId(),"held"); scheduler.drive(run.getId(),1L);
        verify(caller,times(1)).callIsolated(anyList(),anyList(),any());
        assertThatThrownBy(()->scheduler.drive(run.getId(),999L)).isInstanceOf(BizException.class);
        OrgContext.set(2L);
        assertThatThrownBy(()->scheduler.drive(run.getId(),1L)).isInstanceOf(BizException.class);
    }
    @Test void submittedFailedActionCannotBeRetriedAsANewWrite() {
        var run=initialize(node(1,"create"));
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(create("-failed")); scheduler.drive(run.getId(),1L);
        var action=tasks.findByRunIdOrderByIdAsc(run.getId()).getFirst();
        tasks.claimForRun(action.getId(),"PENDING_CONFIRM","RUNNING",java.time.LocalDateTime.now());
        tasks.finish(action.getId(),"FAILED","{}",java.time.LocalDateTime.now()); state.settle(action);
        assertThatThrownBy(()->state.retry(run.getId(),state.nodes(run.getId()).getFirst().getId(),1L)).isInstanceOf(BizException.class);
        drain(); verify(caller,times(1)).callIsolated(anyList(),anyList(),any());
    }
    @Test void expiredDriverRecoveryKeepsTheInterruptedAttemptAndRequiresExplicitRetry() {
        var run=initialize(node(1,"text"));
        state.acquire(run.getId(),"crashed");
        var node=state.begin(run.getId(),state.nodes(run.getId()).getFirst().getId(),"crashed");
        var persisted=runs.findById(run.getId()).orElseThrow();
        persisted.setDispatchStartedAt(java.time.LocalDateTime.now().minusMinutes(31)); runs.saveAndFlush(persisted);
        state.recover(run.getId(),1L);
        assertThat(state.nodes(run.getId()).getFirst().getStatus()).isEqualTo("FAILED");
        assertThat(steps.findByRunIdOrderBySeqAsc(run.getId())).extracting(AgentStep::getStatus).containsExactly("FAILED");
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(LlmProvider.ChatResponse.text("已重试","test"));
        state.retry(run.getId(),node.getId(),1L); scheduler.drive(run.getId(),1L);
        assertThat(steps.findByRunIdOrderBySeqAsc(run.getId())).extracting(AgentStep::getStatus).containsExactly("FAILED","SUCCESS");
        assertThatThrownBy(()->state.complete(node,new SpecialistAgent.Result("generalist","旧结果",List.of()),1L,"crashed")).isInstanceOf(BizException.class);
    }
    @Test void lateFormSubmissionCannotResurrectASupersededRevision() {
        var run=initialize(node(1,"missing-args"));
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(LlmProvider.ChatResponse.calling(List.of(new LlmProvider.ToolCallRequest("form","createActivity",Map.of()))));
        scheduler.drive(run.getId(),1L);
        var stale=tasks.findByRunIdOrderByIdAsc(run.getId()).getFirst();
        assertThat(stale.getStatus()).isEqualTo("WAITING_INPUT");
        var node=state.nodes(run.getId()).getFirst(); state.revise(run.getId(),node.getId(),1L,1,"new instruction");
        stale.setPlanJson("{}");
        assertThatThrownBy(()->tasks.saveAndFlush(stale)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(tasks.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->gate.submitInput(stale.getId(),Map.of("name","late"),ctx)).isInstanceOf(BizException.class);
    }
    @Test void readonlyModeSurvivesPersistenceAndTargetedRetry() {
        var run=runService.startRun(null,1L,"readonly","P1");runIds.add(run.getId());
        var bb=new Blackboard();bb.setPlan(List.of(node(1,"create")));state.initialize(run.getId(),bb,ctx.withMode("readonly"));
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(create("-readonly"));
        scheduler.drive(run.getId(),1L);
        var n=state.nodes(run.getId()).getFirst();assertThat(n.getStatus()).isEqualTo("FAILED");
        state.retry(run.getId(),n.getId(),1L);scheduler.drive(run.getId(),1L);
        assertThat(tasks.findByRunIdOrderByIdAsc(run.getId())).isEmpty();
        assertThat(runs.findById(run.getId()).orElseThrow().getToolMode()).isEqualTo("readonly");
    }
    @Test void sensitiveOutputIsNotCopiedIntoNodeOrAttemptStorage() {
        var run=initialize(node(1,"查询敏感信息")); state.acquire(run.getId(),"storage");
        var node=state.begin(run.getId(),state.nodes(run.getId()).getFirst().getId(),"storage");
        var result=new SpecialistAgent.Result("generalist","张三，手机号13812345678",List.of(),"SUCCESS","test",false,Sensitivity.SENSITIVE,null,null,List.of(),List.of());
        state.complete(node,result,1L,"storage"); state.release(run.getId(),"storage");
        assertThat(state.nodes(run.getId()).getFirst().getOutputText()).doesNotContain("张三","13812345678");
        assertThat(steps.findByRunIdOrderBySeqAsc(run.getId()).getFirst().getOutputJson()).doesNotContain("张三","13812345678");
    }

    @Test void requestCanBeCancelledBeforeItBindsToAStream() {
        String id=UUID.randomUUID().toString();
        requests.cancel(id,1L);
        var bound=publisher.bind(ctx.getStreamId(),ctx.withRequestId(id));
        assertThat(bound.getRequestId()).isEqualTo(id);
        assertThat(publisher.isAborted(ctx.getStreamId())).isTrue();
        assertThat(requests.owned(id,1L).getStatus()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->requests.begin(id,"duplicate",ctx)).isInstanceOf(BizException.class);
        assertThatThrownBy(()->requests.owned(id,2L)).isInstanceOf(BizException.class);
        db.update("delete from agent_request where id=?",id);
    }
    @Test void criticalEventAndNodeStateRollBackTogether() {
        var run=initialize(node(1,"atomic"));long before=runs.findById(run.getId()).orElseThrow().getEventSeq();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(tx);
        assertThatThrownBy(()->transaction.execute(status->{
            state.cancel(run.getId(),1L);throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(runs.findById(run.getId()).orElseThrow().getEventSeq()).isEqualTo(before);
        assertThat(state.nodes(run.getId()).getFirst().getStatus()).isEqualTo("QUEUED");
        var rows=eventRows.findByRunIdAndEventSeqGreaterThanOrderByEventSeqAsc(run.getId(),0,org.springframework.data.domain.PageRequest.of(0,100));
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().isWakeRequired()).isTrue();
        assertThat(rows.getFirst().getStatus()).isEqualTo("RUNNING");
    }
    @Test void durableWakeResumesWhenTheInMemoryNotificationWasLost() {
        var run=initialize(node(1,"durable"));
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(LlmProvider.ChatResponse.text("持久任务已执行","test"));
        // Only this fixture's events are pending; previous test records may be retained by other suites.
        db.update("update agent_run_event set delivered_at=CURRENT_TIMESTAMP where run_id<>?",run.getId());
        queue.clear();outbox.deliverOnce();outbox.deliverOnce();
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("SUCCESS");
        assertThat(state.nodes(run.getId()).getFirst().getAttempt()).isEqualTo(1);
        verify(caller,times(1)).callIsolated(anyList(),anyList(),any());
    }
    @Test void recoveryNeverReplaysAnUncertainBusinessCommit() {
        var run=initialize(node(1,"commit"),node(2,"downstream",1));
        when(caller.callIsolated(anyList(),anyList(),any())).thenAnswer(i->create("-uncertain"));
        scheduler.drive(run.getId(),1L);
        var action=tasks.findByRunIdOrderByIdAsc(run.getId()).getFirst();
        tasks.claimOwned(action.getId(),"PENDING_CONFIRM","RUNNING",java.time.LocalDateTime.now().minusHours(1),"previous-process");
        state.recover(run.getId(),1L);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("NEEDS_RECONCILE");
        assertThat(state.nodes(run.getId())).extracting(AgentWorkItem::getStatus).containsExactly("NEEDS_RECONCILE","QUEUED");
        state.cancel(run.getId(),1L);scheduler.drive(run.getId(),1L);
        assertThat(state.nodes(run.getId()).getLast().getAttempt()).isZero();
        assertThatThrownBy(()->state.retry(run.getId(),state.nodes(run.getId()).getFirst().getId(),1L)).isInstanceOf(Exception.class);
        verify(caller,times(1)).callIsolated(anyList(),anyList(),any());
    }
    @Test void stopCancelsPendingActionsAndQueuedDescendants() {
        var run=initialize(node(1,"pending"),node(2,"never-start",1));
        when(caller.callIsolated(anyList(),anyList(),any())).thenAnswer(i->create("-stop"));
        scheduler.drive(run.getId(),1L);var action=tasks.findByRunIdOrderByIdAsc(run.getId()).getFirst();
        assertThat(state.cancel(run.getId(),1L)).isEqualTo("CANCELLED");
        gate.confirm(action.getId(),action.getConfirmToken(),ctx);
        assertThat(tasks.findById(action.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(state.nodes(run.getId())).allSatisfy(n->assertThat(n.getStatus()).isEqualTo("CANCELLED"));
    }
    @Test void blockingReviewHasLocationAndRevisionAndNoUsableToken() {
        var run=initialize(node(1,"日期应为2026-11-20"));
        state.acquire(run.getId(),"review");var node=state.begin(run.getId(),state.nodes(run.getId()).getFirst().getId(),"review");
        var local=ctx.withRunId(run.getId()).withWorkItem(node.getId(),node.getRevision()).withAttempt(node.getAttempt());
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(LlmProvider.ChatResponse.text("{\"issues\":[{\"severity\":\"BLOCKING\",\"quote\":\"2025-11-20\",\"reason\":\"日期与任务不一致，请按2026年修订\",\"sourceRef\":\"task\"}]}","test"));
        var tool=toolRegistry.find("draftArticleFromActivity").orElseThrow();
        var plan=gate.createPlan(local,tool,"待审正文",1,List.of(),List.of(),List.of(),false,"活动在2025-11-20举行。");
        assertThat(plan.confirmToken()).isEmpty();
        assertThat(tasks.findById(plan.planId()).orElseThrow().getStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(issues.findByRunIdOrderByIdAsc(run.getId())).singleElement().satisfies(i->{
            assertThat(i.getQuoteText()).isEqualTo("2025-11-20");assertThat(i.getLocation()).contains("正文第 1 行");assertThat(i.getRevision()).isEqualTo(1);
        });
        state.release(run.getId(),"review");state.revise(run.getId(),node.getId(),1L,1,"改为2026-11-20");
        assertThat(tasks.findById(plan.planId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(state.nodes(run.getId()).getFirst().getReviewStatus()).isEqualTo("NOT_REVIEWED");
    }

    @Test void ordinaryStopInvalidatesItsPreviewAndCannotCreateAnotherAfterCancellation() {
        String requestId=UUID.randomUUID().toString();var local=publisher.bind(ctx.getStreamId(),ctx.withRequestId(requestId));
        var tool=toolRegistry.find("createActivity").orElseThrow();
        var plan=gate.createPlan(local,tool,"ordinary",0,List.of(),List.of(),List.of(),false);
        requests.cancel(requestId,1L);gate.cancelUnsubmitted(local.getStreamId(),1L);
        assertThat(tasks.findById(plan.planId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->gate.createPlan(local,tool,"late",0,List.of(),List.of(),List.of(),false)).isInstanceOf(java.util.concurrent.CancellationException.class);
        db.update("delete from agent_task where id=?",plan.planId());db.update("delete from agent_request where id=?",requestId);
    }
    @Test void eventDeliveryLeaseAllowsOnlyOneClaimAndCanBeReclaimedAfterExpiry() {
        var run=initialize(node(1,"lease"));var row=eventRows.findByRunIdAndEventSeqGreaterThanOrderByEventSeqAsc(run.getId(),0,org.springframework.data.domain.PageRequest.of(0,10)).getFirst();
        var now=java.time.LocalDateTime.now();
        assertThat(eventRows.claim(row.getId(),"one",now,now.plusSeconds(60))).isEqualTo(1);
        assertThat(eventRows.claim(row.getId(),"two",now,now.plusSeconds(60))).isZero();
        assertThat(eventRows.claim(row.getId(),"two",now.plusSeconds(61),now.plusSeconds(120))).isEqualTo(1);
        assertThat(eventRows.delivered(row.getId(),"one",now)).isZero();
        assertThat(eventRows.delivered(row.getId(),"two",now)).isEqualTo(1);
        assertThat(eventRows.claim(row.getId(),"three",now.plusHours(1),now.plusHours(2))).isZero();
    }

    @Test void cancelledChatPersistsAnAssistantForHistoryRecovery() {
        when(caller.callLlm(anyList(),anyList(),any(),any(),anyInt())).thenThrow(new java.util.concurrent.CancellationException());
        var result=orchestrator.chatSync("停止记录",null,ctx,List.of(),false);
        Long conversation=((Number)result.get("conversationId")).longValue();
        String stream=result.get("streamId").toString();
        assertThat(messageRows.findByStreamIdAndRole(stream,"ASSISTANT")).singleElement().satisfies(m->{
            assertThat(m.getExecutionState()).isEqualTo("CANCELLED");assertThat(m.getContent()).contains("停止");
        });
        db.update("delete from agent_message where conversation_id=?",conversation);
        db.update("delete from agent_request where stream_id=?",stream);
        db.update("delete from agent_conversation where id=?",conversation);
    }

    @Test void aBusinessCommitClaimedBeforeStopKeepsItsRealResultAndNeverStartsDescendants() throws Exception {
        var run=initialize(node(1,"started-write"),node(2,"must-not-run",1));
        when(caller.callIsolated(anyList(),anyList(),any())).thenAnswer(i->create("-inflight"));
        scheduler.drive(run.getId(),1L);
        var action=tasks.findByRunIdOrderByIdAsc(run.getId()).getFirst();
        assertThat(state.claimAction(action,ctx)).isEqualTo(1);
        assertThat(state.cancel(run.getId(),1L)).isEqualTo("STOPPING");
        long before=activities.count();
        var tool=(WriteAgentTool)toolRegistry.find("createActivity").orElseThrow();
        var preview=tool.execute(ctx,Map.of("name",prefix+"-inflight","startDate","2026-11-20")).plan();
        var committed=tool.commit(ctx,preview);
        tasks.finish(action.getId(),committed.resultStatus(),json.writeValueAsString(Map.of("status",committed.status(),"references",committed.references())),java.time.LocalDateTime.now());
        state.settle(action);drain();
        assertThat(activities.count()).isEqualTo(before+1);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("PARTIAL");
        assertThat(state.nodes(run.getId())).extracting(AgentWorkItem::getStatus).containsExactly("SUCCESS","CANCELLED");
        assertThat(state.nodes(run.getId()).getLast().getAttempt()).isZero();
        gate.confirm(action.getId(),action.getConfirmToken(),ctx);
        assertThat(activities.count()).isEqualTo(before+1);
    }
}
