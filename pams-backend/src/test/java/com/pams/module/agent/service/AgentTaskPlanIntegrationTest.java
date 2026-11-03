package com.pams.module.agent.service;
import com.pams.common.OrgContext;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.tool.*;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class AgentTaskPlanIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry){AgentBatchFiveDatabase.configure(registry);}
    @Autowired PrepareTaskPlanChangeTool tool;@Autowired GenerateActivityTasksTool generate;@Autowired AgentTaskPlanService plans;
    @Autowired TaskRepository tasks;@Autowired ActivityRepository activities;@Autowired TaskGate gate;
    @Autowired QueryActivityTasksTool query;@Autowired ActionReconciliationService reconciliation;
    @Autowired com.pams.module.agent.repository.AgentTaskRepository actions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    final List<Long> actionIds=new ArrayList<>();final List<String> operationIds=new ArrayList<>();
    Activity activity;ToolContext ctx;
    @BeforeEach void setup(){OrgContext.set(1L);ctx=new ToolContext(new LoginUser(1L,"teacher","测试","TEACHER",5,null,null,1L),List.of("task:manage","task:view","activity:view").stream().map(SimpleGrantedAuthority::new).toList(),null,null);
        activity=new Activity();activity.setName("任务计划闭环");activity.setType("OTHER");activity.setStatus(ActivityStatus.EXECUTING);activity.setDeleted(0);activity=activities.saveAndFlush(activity);}
    @AfterEach void clear(){
        if(activity!=null && !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){
            for(Long id:actionIds)db.update("DELETE FROM agent_task WHERE id=?",id);for(String id:operationIds)db.update("DELETE FROM agent_business_operation WHERE id=?",id);
            db.update("DELETE FROM task WHERE activity_id=?",activity.getId());db.update("DELETE FROM activity WHERE id=?",activity.getId());
        }OrgContext.clear();}
    String baseline(){return AgentTaskPlanService.baseline(tasks.findByActivityIdOrderByStartDateAsc(activity.getId()));}
    Map<String,Object> create(String name){return Map.of("activityId",activity.getId(),"planBaseline",baseline(),"changes",Map.of("name",name,"startDate","2026-12-01","endDate","2026-12-02"));}
    AgentActionPlan pending(AgentTool t,ToolResult prepared){assertThat(prepared.plan()).as(prepared.summary()).isNotNull();var p=prepared.plan();return gate.createPlan(ctx,t,p.summary(),p.total(),p.items(),p.skipped(),p.detailLines(),false,p.preview());}
    @Test void newTaskAndExplicitPatchUsePreviewSaveAndDoNotClearUnrelatedFields(){
        var p=pending(tool,tool.execute(ctx,create("新任务")));assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).isEmpty();
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx).get("status")).isEqualTo("SUCCESS");
        var task=tasks.findByActivityIdOrderByStartDateAsc(activity.getId()).getFirst();Long id=task.getId();Long revision=task.getRevision();
        var patch=Map.<String,Object>of("activityId",activity.getId(),"taskId",id,"expectedRevision",revision,"planBaseline",baseline(),"changes",Map.of("description","只更新说明"));
        var update=pending(tool,tool.execute(ctx,patch));assertThat(gate.confirm(update.planId(),update.confirmToken(),ctx).get("status")).isEqualTo("SUCCESS");
        var saved=tasks.findById(id).orElseThrow();assertThat(saved.getName()).isEqualTo("新任务");assertThat(saved.getStartDate()).isEqualTo(java.time.LocalDate.parse("2026-12-01"));assertThat(saved.getDescription()).isEqualTo("只更新说明");
        gate.confirm(update.planId(),update.confirmToken(),ctx);assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).hasSize(1);
    }
    @Test void explicitSuggestionCompatibilityAndQueryExposeRealPlanBaseline(){
        var result=query.execute(ctx,Map.of("activityId",activity.getId()));assertThat(result.payload()).containsEntry("planBaseline",baseline());
        var p=pending(generate,generate.execute(ctx,Map.of("activityId",activity.getId(),"planBaseline",baseline(),"tasks",List.of(Map.of("name","明确建议")))));
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx).get("status")).isEqualTo("SUCCESS");assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).singleElement().satisfies(t->assertThat(t.getName()).isEqualTo("明确建议"));
    }
    @Test void staleGraphCrossTenantAndDeletionOrUnboundAssigneeAreRejected(){
        var p=pending(tool,tool.execute(ctx,create("待保存")));
        var extra=new Task();extra.setActivityId(activity.getId());extra.setName("并发任务");extra.setProgress(0);extra.setStatus(Task.TaskStatus.TODO);extra.setDeleted(0);tasks.saveAndFlush(extra);
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx).get("status")).isEqualTo("FAILED");
        var invalid=new HashMap<>(create("非法变更"));invalid.put("changes",Map.of("deleted",1));assertThat(tool.execute(ctx,invalid).plan()).isNull();
        invalid.put("changes",Map.of("name","非法负责人","assigneeUserId",Long.MAX_VALUE));assertThat(tool.execute(ctx,invalid).plan()).isNull();
        var foreign=new ToolContext(new LoginUser(1L,"u","u","TEACHER",5,null,null,2L),ctx.getAuthorities().stream().map(SimpleGrantedAuthority::new).toList(),null,null);assertThat(tool.execute(foreign,create("跨组织")).plan()).isNull();
    }
    @Test void taskQueryPagesHaveStableCountsAndNoFalseEmptySummary(){
        for(int i=0;i<7;i++){var t=new Task();t.setActivityId(activity.getId());t.setName("任务"+i);t.setProgress(0);t.setStatus(Task.TaskStatus.TODO);t.setDeleted(0);tasks.saveAndFlush(t);}
        var first=query.execute(ctx,Map.of("activityId",activity.getId()));assertThat(first.payload()).containsEntry("total",7).containsEntry("returned",5).containsEntry("hasMore",true);
        var second=query.execute(ctx,Map.of("activityId",activity.getId(),"page",1));assertThat(second.payload()).containsEntry("returned",2).containsEntry("hasMore",false);
        assertThat(query.execute(ctx,Map.of("activityId",activity.getId(),"page",2)).summary()).contains("当前页");
    }
    AgentActionPlan running(ToolResult prepared)throws Exception{
        return running(tool,prepared);
    }
    AgentActionPlan running(AgentTool kind,ToolResult prepared)throws Exception{
        var raw=prepared.plan();assertThat(raw).as(prepared.summary()).isNotNull();
        var task=new com.pams.module.agent.entity.AgentTask();task.setUserId(1L);task.setToolName(kind.name());task.setStatus("RUNNING");task.setCreatedAt(java.time.LocalDateTime.now());task.setPlanJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw.toJson()));task=actions.saveAndFlush(task);actionIds.add(task.getId());
        for(String line:raw.detailLines())if(line.startsWith("__args__:"))operationIds.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.substring(9)).path("operationId").asText());
        return new AgentActionPlan(task.getId(),raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,null,raw.preview());
    }
    @Test void expiredUncommittedOperationCannotBeReplayed()throws Exception{
        var action=running(tool.execute(ctx,create("过期任务")));db.update("UPDATE agent_business_operation SET expires_at=? WHERE id=?",java.time.LocalDateTime.now().minusSeconds(1),operationIds.getFirst());
        assertThatThrownBy(()->plans.commit(ctx,action)).hasMessageContaining("过期");
        actions.finish(action.planId(),"RESULT_UNKNOWN",null,java.time.LocalDateTime.now());assertThat(reconciliation.reconcile(action.planId(),ctx)).containsEntry("reconciled",false);assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).isEmpty();
    }
    @Test void readonlyAndCancelledTaskCannotCommit()throws Exception{
        assertThat(tool.execute(ctx.withMode("readonly"),create("只读")).plan()).isNull();
        var action=running(tool.execute(ctx,create("已取消")));actions.finish(action.planId(),"CANCELLED",null,java.time.LocalDateTime.now());
        assertThatThrownBy(()->plans.commit(ctx,action)).hasMessageContaining("状态无效");assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).isEmpty();
    }
    @Test void committedTaskPlanReceiptCanBeReconciledWithoutCreatingAgain()throws Exception{
        var action=running(tool.execute(ctx,create("回执任务")));plans.commit(ctx,action);actions.finish(action.planId(),"RESULT_UNKNOWN",null,java.time.LocalDateTime.now());
        assertThat(reconciliation.reconcile(action.planId(),ctx)).containsEntry("reconciled",true);assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).hasSize(1);
    }
    @Test void cascadingDependencyChangesAreRefusedInsteadOfSilentlyChangingOtherTasks(){
        var a=new Task();a.setActivityId(activity.getId());a.setName("前置");a.setDeleted(0);a.setProgress(0);a.setStatus(Task.TaskStatus.TODO);a.setStartDate(java.time.LocalDate.parse("2026-12-01"));a.setEndDate(java.time.LocalDate.parse("2026-12-02"));a=tasks.saveAndFlush(a);
        var b=new Task();b.setActivityId(activity.getId());b.setName("后续");b.setDeleted(0);b.setProgress(0);b.setStatus(Task.TaskStatus.TODO);b.setDependsOn(a.getId());b.setStartDate(java.time.LocalDate.parse("2026-12-03"));b.setEndDate(java.time.LocalDate.parse("2026-12-04"));tasks.saveAndFlush(b);
        var result=tool.execute(ctx,Map.of("activityId",activity.getId(),"planBaseline",baseline(),"taskId",a.getId(),"expectedRevision",a.getRevision(),"changes",Map.of("endDate","2026-12-05")));
        assertThat(result.plan()).isNull();assertThat(result.summary()).contains("CONFLICT");assertThat(tasks.findById(b.getId()).orElseThrow().getStartDate()).isEqualTo(java.time.LocalDate.parse("2026-12-03"));
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentCreateUsesOneStableReceipt()throws Exception{
        var action=running(tool.execute(ctx,create("并发创建")));var latch=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var jobs=new ArrayList<java.util.concurrent.Future<WriteAgentTool.CommitResult>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{OrgContext.set(1L);try{latch.await();return plans.commit(ctx,action);}finally{OrgContext.clear();}}));latch.countDown();
            var a=jobs.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS);var b=jobs.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS);assertThat(a.detail()).isEqualTo(b.detail());
        }assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).hasSize(1);
    }
    @Test void multipleSuggestionsAndStandardTemplateAreAtomicAndSkipExistingNames()throws Exception{
        var p=pending(generate,generate.execute(ctx,Map.of("activityId",activity.getId(),"planBaseline",baseline(),"tasks",List.of(Map.of("name","明确一"),Map.of("name","明确二")))));
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx).get("status")).isEqualTo("SUCCESS");assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).hasSize(2);
        actions.finish(p.planId(),"RESULT_UNKNOWN",null,java.time.LocalDateTime.now());assertThat(reconciliation.reconcile(p.planId(),ctx)).containsEntry("reconciled",true);
        var standard=pending(generate,generate.execute(ctx,Map.of("activityId",activity.getId())));assertThat(gate.confirm(standard.planId(),standard.confirmToken(),ctx).get("status")).isEqualTo("SUCCESS");
        assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).hasSize(12);assertThat(generate.execute(ctx,Map.of("activityId",activity.getId())).plan()).isNull();
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void failureInLaterBatchItemRollsBackEarlierTaskAndReceipt()throws Exception{
        Long staff=db.queryForObject("SELECT id FROM sys_user WHERE username='staff'",Long.class);
        Long dept=db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?",Long.class,staff);
        var raw=generate.execute(ctx,Map.of("activityId",activity.getId(),"planBaseline",baseline(),"tasks",List.of(Map.of("name","先执行"),Map.of("name","后失败","assigneeUserId",staff,"deptId",dept))));
        var action=running(generate,raw);int previous=db.queryForObject("SELECT status FROM sys_user WHERE id=?",Integer.class,staff);
        try{db.update("UPDATE sys_user SET status=0 WHERE id=?",staff);assertThatThrownBy(()->plans.commitGenerated(ctx,action)).hasMessageContaining("负责人");assertThat(tasks.findByActivityIdOrderByStartDateAsc(activity.getId())).isEmpty();
            assertThat(db.queryForObject("SELECT result_json FROM agent_business_operation WHERE id=?",String.class,operationIds.getFirst())).isNull();
        }finally{db.update("UPDATE sys_user SET status=? WHERE id=?",previous,staff);}
    }
}
