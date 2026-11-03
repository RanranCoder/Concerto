package com.pams.module.agent.service;

import com.pams.common.OrgContext;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.agent.tool.PrepareTaskProgressUpdateTool;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class TaskProgressActionIntegrationTest {
    @Autowired PrepareTaskProgressUpdateTool tool;
    @Autowired TaskProgressActionService progress;
    @Autowired TaskRepository tasks;
    @Autowired ActivityRepository activities;
    @Autowired AgentTaskRepository actions;
    @Autowired TaskGate gate;
    @Autowired ActionReconciliationService reconciliation;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    final List<Long> actionIds=new ArrayList<>();
    Task task;Activity activity;ToolContext ctx;
    @BeforeEach void setup() {
        OrgContext.set(1L);
        ctx=context(1L,1L,"task:manage","task:progress");
        activity=new Activity();activity.setName("任务进度闭环");activity.setType("OTHER");activity.setStatus(ActivityStatus.EXECUTING);activity.setDeleted(0);activity=activities.saveAndFlush(activity);
        task=new Task();task.setActivityId(activity.getId());task.setName("核验任务");task.setAssigneeUserId(1L);task.setAssignee("测试");
        task.setProgress(0);task.setStatus(Task.TaskStatus.TODO);task.setDeleted(0);task.setStartDate(LocalDate.now());task.setEndDate(LocalDate.now().plusDays(1));task=tasks.saveAndFlush(task);
    }
    @AfterEach void clear(){
        if(task!=null && !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            db.update("DELETE FROM agent_task_progress_operation WHERE business_task_id=?",task.getId());
            for(Long id:actionIds) db.update("DELETE FROM agent_task WHERE id=?",id);
            db.update("DELETE FROM task WHERE id=?",task.getId());db.update("DELETE FROM activity WHERE id=?",activity.getId());
        }
        OrgContext.clear();
    }
    ToolContext context(Long uid,Long org,String... permissions) {
        return new ToolContext(new LoginUser(uid,"teacher","测试","TEACHER",5,null,null,org),Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList(),null,null);
    }
    Map<String,Object> args(){return Map.of("taskId",task.getId(),"expectedRevision",task.getRevision(),"progress",50);}
    AgentActionPlan action(AgentActionPlan raw) throws Exception {
        var pending=new AgentTask();pending.setUserId(1L);pending.setToolName(tool.name());pending.setStatus("RUNNING");pending.setCreatedAt(LocalDateTime.now());
        pending.setPlanJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw.toJson()));pending=actions.saveAndFlush(pending);
        actionIds.add(pending.getId());
        return new AgentActionPlan(pending.getId(),raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,null,raw.preview());
    }
    @Test void previewAndRealConfirmationUpdateOnlyProgressAndRepeatedConfirmationIsIdempotent() {
        var prepared=tool.execute(ctx,args());assertThat(prepared.plan()).isNotNull();assertThat(tasks.findById(task.getId()).orElseThrow().getProgress()).isZero();
        var raw=prepared.plan();var pending=gate.createPlan(ctx,tool,raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,raw.preview());
        var result=gate.confirm(pending.planId(),pending.confirmToken(),ctx);assertThat(result.get("status")).as(result.toString()).isEqualTo("SUCCESS");
        var saved=tasks.findById(task.getId()).orElseThrow();assertThat(saved.getProgress()).isEqualTo(50);assertThat(saved.getStatus()).isEqualTo(Task.TaskStatus.DOING);
        assertThat(saved.getAssigneeUserId()).isEqualTo(1L);assertThat(saved.getName()).isEqualTo("核验任务");long revision=saved.getRevision();
        gate.confirm(pending.planId(),pending.confirmToken(),ctx);assertThat(tasks.findById(task.getId()).orElseThrow().getRevision()).isEqualTo(revision);
    }
    @Test void selfOnlyScopeRejectsOtherAssigneesAndCrossTenantEvenForManager() {
        var self=context(1L,1L,"task:progress");assertThat(tool.execute(self,args()).plan()).isNotNull();
        task.setAssigneeUserId(2L);tasks.saveAndFlush(task);
        assertThatThrownBy(()->tool.execute(self,args())).hasMessageContaining("本人");
        var otherOrg=context(1L,2L,"task:manage");assertThatThrownBy(()->tool.execute(otherOrg,args())).hasMessageContaining("不可见");
    }
    @Test void stalePreviewPermissionChangesAndArchivedActivitiesAreRejected() throws Exception {
        var prepared=tool.execute(ctx,args());var pending=action(prepared.plan());
        task.setDescription("并发更新");tasks.saveAndFlush(task);
        assertThatThrownBy(()->progress.commit(ctx,pending)).hasMessageContaining("STALE_BASELINE");
        assertThatThrownBy(()->tool.execute(ctx.withMode("readonly"),args())).hasMessageContaining("无权");
        activity.setStatus(ActivityStatus.ARCHIVED);activities.saveAndFlush(activity);
        assertThatThrownBy(()->tool.execute(ctx,args())).hasMessageContaining("归档");
    }
    @Test void receiptReconciliationUsesHistoricalOperationInsteadOfInferringFromCurrentProgress() throws Exception {
        var prepared=tool.execute(ctx,args());var pending=action(prepared.plan());var committed=progress.commit(ctx,pending);
        actions.finish(pending.planId(),"RESULT_UNKNOWN",null,LocalDateTime.now());
        var result=reconciliation.reconcile(pending.planId(),ctx);assertThat(result).containsEntry("reconciled",true).containsEntry("status","SUCCESS");
        var saved=tasks.findById(task.getId()).orElseThrow();long revision=saved.getRevision();
        assertThat(committed.detail()).containsEntry("progress",50);reconciliation.reconcile(pending.planId(),ctx);
        assertThat(tasks.findById(task.getId()).orElseThrow().getRevision()).isEqualTo(revision);
    }
    @Test void missingReceiptDoesNotReplayAndInvalidFractionalOrOutOfRangeProgressIsRejected() throws Exception {
        var prepared=tool.execute(ctx,args());var pending=action(prepared.plan());actions.finish(pending.planId(),"RESULT_UNKNOWN",null,LocalDateTime.now());
        assertThat(reconciliation.reconcile(pending.planId(),ctx)).containsEntry("reconciled",false);
        assertThat(tasks.findById(task.getId()).orElseThrow().getProgress()).isZero();
        for(Object value:List.of(101,-1,50.5,Long.MAX_VALUE)) {
            var invalid=new HashMap<>(args());invalid.put("progress",value);assertThat(tool.execute(ctx,invalid).plan()).isNull();
        }
    }
    @Test void changedAssigneeOrCancelledConfirmationCannotWrite() throws Exception {
        var self=context(1L,1L,"task:progress");var prepared=tool.execute(self,args());var pending=action(prepared.plan());
        task.setAssigneeUserId(2L);tasks.saveAndFlush(task);
        assertThatThrownBy(()->progress.commit(self,pending)).hasMessageContaining("本人");
        var cancelled=actions.findById(pending.planId()).orElseThrow();cancelled.setStatus("CANCELLED");actions.saveAndFlush(cancelled);
        assertThatThrownBy(()->progress.commit(ctx,pending)).hasMessageContaining("状态无效");
        assertThat(tasks.findById(task.getId()).orElseThrow().getProgress()).isZero();
    }
    @Test void secondOperationFromSameBaselineIsRejectedInsteadOfOverwritingFirstReceipt() throws Exception {
        var first=action(tool.execute(ctx,args()).plan());
        var secondArgs=new HashMap<>(args());secondArgs.put("progress",75);var second=action(tool.execute(ctx,secondArgs).plan());
        progress.commit(ctx,first);
        assertThatThrownBy(()->progress.commit(ctx,second)).hasMessageContaining("STALE_BASELINE");
        assertThat(tasks.findById(task.getId()).orElseThrow().getProgress()).isEqualTo(50);
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentCommitReusesOneReceiptAndIncrementsRevisionOnce() throws Exception {
        var prepared=tool.execute(ctx,args());var pending=action(prepared.plan());long initialRevision=task.getRevision();
        var latch=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<java.util.concurrent.Future<WriteAgentTool.CommitResult>>();
            for(int i=0;i<2;i++) jobs.add(pool.submit(()->{OrgContext.set(1L);try{latch.await();return progress.commit(ctx,pending);}finally{OrgContext.clear();}}));
            latch.countDown();
            var a=jobs.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS);var b=jobs.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.detail()).isEqualTo(b.detail());
            var saved=tasks.findById(task.getId()).orElseThrow();assertThat(saved.getRevision()).isEqualTo(initialRevision+1);assertThat(saved.getProgress()).isEqualTo(50);
        }
    }
}
