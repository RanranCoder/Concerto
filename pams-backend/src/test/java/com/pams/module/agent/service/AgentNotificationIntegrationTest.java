package com.pams.module.agent.service;
import com.pams.common.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.agent.tool.SendNotificationTool;
import com.pams.module.notification.service.*;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class AgentNotificationIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry){AgentBatchFiveDatabase.configure(registry);}
    @Autowired AgentNotificationActionService notifications;@Autowired SendNotificationTool tool;@Autowired TaskGate gate;
    @Autowired NotificationOutboxWorker worker;@Autowired NotificationService inbox;@Autowired JdbcTemplate db;
    @Autowired AgentTaskRepository tasks;@Autowired ActionReconciliationService reconciliation;
    ToolContext ctx;Long owner,recipient;String title;
    @BeforeEach void setup(){OrgContext.set(1L);owner=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);recipient=db.queryForObject("SELECT id FROM sys_user WHERE username='staff'",Long.class);
        ctx=context(owner,1L);title="Agent闭环-"+UUID.randomUUID();}
    @AfterEach void clear(){OrgContext.clear();}
    ToolContext context(Long user,Long org){return new ToolContext(new LoginUser(user,"u","测试","TEACHER",5,null,null,org),List.of(new SimpleGrantedAuthority("notification:send")),null,null);}
    Map<String,Object> selection(){return Map.of("userIds",List.of(recipient),"combination","UNION");}
    String audience(){return notifications.resolve(ctx,selection()).payload().get("audienceRef").toString();}
    ToolResult prepare(){return tool.execute(ctx,Map.of("audienceRef",audience(),"title",title,"content","正文内容"));}
    AgentActionPlan pending(ToolResult prepared){assertThat(prepared.plan()).as(prepared.summary()).isNotNull();var p=prepared.plan();return gate.createPlan(ctx,tool,p.summary(),p.total(),p.items(),p.skipped(),p.detailLines(),false,p.preview());}
    long id(AgentActionPlan p){var result=gate.confirm(p.planId(),p.confirmToken(),ctx);assertThat(result.get("status")).as(result.toString()).isEqualTo("SUCCESS");@SuppressWarnings("unchecked")var saved=(Map<String,Object>)result.get("result");@SuppressWarnings("unchecked")var detail=(Map<String,Object>)saved.get("detail");return ((Number)detail.get("notificationId")).longValue();}
    @Test void realOutboxInboxAndReadReceiptAreDifferentAndRepeatedConfirmDoesNotResend(){
        var p=pending(prepare());long before=db.queryForObject("SELECT COUNT(*) FROM notification WHERE title=?",Long.class,title);assertThat(before).isZero();
        long id=id(p);var accepted=notifications.status(ctx,id);assertThat(accepted.payload()).containsEntry("submission","ACCEPTED").containsEntry("inboxCount",0L).containsEntry("readCount",0L).containsEntry("allInboxDelivered",false);
        worker.tick();var delivered=notifications.status(ctx,id);assertThat(delivered.payload()).containsEntry("inboxCount",1L).containsEntry("readCount",0L).containsEntry("materializationComplete",true).containsEntry("allInboxDelivered",true);
        inbox.markAsRead(id,recipient);assertThat(notifications.status(ctx,id).payload()).containsEntry("readCount",1L);
        gate.confirm(p.planId(),p.confirmToken(),ctx);worker.tick();assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE title=?",Long.class,title)).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id=?",Long.class,id)).isEqualTo(1L);
    }
    @Test @Transactional void explicitCombinationNoDefaultAllAndSnapshotPrivacy(){
        var result=notifications.resolve(ctx,selection());assertThat(result.payload()).containsEntry("count",1);assertThat(result.payload()).doesNotContainKeys("userIds","names","phone");
        assertThatThrownBy(()->notifications.resolve(ctx,Map.of("userIds",List.of(recipient)))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->notifications.resolve(ctx,Map.of("combination","UNION"))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->notifications.resolve(ctx,Map.of("userIds",List.of(Long.MAX_VALUE),"combination","UNION"))).isInstanceOf(BizException.class);
        String role=db.queryForObject("SELECT r.code FROM sys_role r JOIN sys_user u ON u.role_id=r.id WHERE u.id=?",String.class,recipient);
        var intersection=notifications.resolve(ctx,Map.of("userIds",List.of(recipient),"roleCodes",List.of(role),"combination","INTERSECTION"));assertThat(intersection.payload()).containsEntry("count",1);
        assertThat(tool.execute(ctx,Map.of("recipientUserId",recipient,"recipientRole",role,"title",title,"content","正文")).plan()).isNull();
    }
    AgentActionPlan running(ToolResult prepared)throws Exception{
        var raw=prepared.plan();assertThat(raw).as(prepared.summary()).isNotNull();var task=new AgentTask();task.setUserId(owner);task.setToolName("sendNotification");task.setStatus("RUNNING");task.setCreatedAt(java.time.LocalDateTime.now());task.setPlanJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw.toJson()));task=tasks.saveAndFlush(task);
        return new AgentActionPlan(task.getId(),raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,null,raw.preview());
    }
    @Test @Transactional void missingReceiptCancelledTaskAndReadOnlyModeNeverSend()throws Exception{
        var action=running(prepare());tasks.finish(action.planId(),"RESULT_UNKNOWN",null,java.time.LocalDateTime.now());
        assertThat(reconciliation.reconcile(action.planId(),ctx)).containsEntry("reconciled",false);
        tasks.finish(action.planId(),"CANCELLED",null,java.time.LocalDateTime.now());assertThatThrownBy(()->notifications.commit(ctx,action)).hasMessageContaining("状态无效");
        assertThat(tool.execute(ctx.withMode("readonly"),Map.of("recipientUserId",recipient,"title",title,"content","正文")).plan()).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE title=?",Long.class,title)).isZero();
    }
    @Test @Transactional void audienceExpansionMustBeRepreviewed(){
        long roleId=db.queryForObject("SELECT role_id FROM sys_user WHERE id=?",Long.class,recipient);String role=db.queryForObject("SELECT code FROM sys_role WHERE id=?",String.class,roleId);
        String ref=notifications.resolve(ctx,Map.of("roleCodes",List.of(role),"combination","UNION")).payload().get("audienceRef").toString();
        var p=pending(tool.execute(ctx,Map.of("audienceRef",ref,"title",title,"content","正文")));
        db.update("INSERT INTO sys_user(org_id,username,password,real_name,role_id,status,deleted) VALUES(1,?,'test','新增账号',?,1,0)","aud-"+UUID.randomUUID(),roleId);
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx).get("status")).isEqualTo("FAILED");assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE title=?",Long.class,title)).isZero();
    }
    @Test void invalidRecipientAfterSubmissionIsSkippedRatherThanReportedDelivered(){
        var p=pending(prepare());long id=id(p);int previous=db.queryForObject("SELECT status FROM sys_user WHERE id=?",Integer.class,recipient);
        try{db.update("UPDATE sys_user SET status=0 WHERE id=?",recipient);worker.tick();assertThat(notifications.status(ctx,id).payload()).containsEntry("inboxCount",0L);
            assertThat(db.queryForObject("SELECT skipped_count FROM notification_outbox WHERE message_id=?",Integer.class,id)).isEqualTo(1);
        }finally{db.update("UPDATE sys_user SET status=? WHERE id=?",previous,recipient);}
    }
    @Test @Transactional void membershipChangeExpiryCrossTenantAndLongContentRequireNewPreview(){
        String ref=audience();var p=pending(tool.execute(ctx,Map.of("audienceRef",ref,"title",title,"content","正文")));
        db.update("UPDATE sys_user SET status=0 WHERE id=?",recipient);
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx).get("status")).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE title=?",Long.class,title)).isZero();
        db.update("UPDATE sys_user SET status=1 WHERE id=?",recipient);db.update("UPDATE agent_notification_audience SET expires_at=? WHERE id=?",java.time.LocalDateTime.now().minusMinutes(1),ref);
        assertThat(tool.execute(ctx,Map.of("audienceRef",ref,"title",title,"content","正文")).plan()).isNull();
        assertThat(tool.execute(context(owner,2L),Map.of("audienceRef",ref,"title",title,"content","正文")).plan()).isNull();
        assertThat(tool.execute(ctx,Map.of("recipientUserId",recipient,"title",title,"content","长".repeat(501))).plan()).isNull();
    }
    @Test @Transactional void receiptReconciliationDoesNotResendAndOtherSendersCannotInspectStatus() throws Exception {
        var p=pending(prepare());long id=id(p);tasks.finish(p.planId(),"RESULT_UNKNOWN",null,java.time.LocalDateTime.now());
        assertThat(reconciliation.reconcile(p.planId(),ctx)).containsEntry("reconciled",true);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE title=?",Long.class,title)).isEqualTo(1L);
        assertThatThrownBy(()->notifications.status(context(recipient,1L),id)).hasMessageContaining("不可见");
    }
    @Test @Transactional void distinctOperationsInOneTransactionDoNotMergeAndRollbackLeaksNoInbox(){
        var first=pending(prepare());long a=id(first);var second=pending(prepare());long b=id(second);assertThat(a).isNotEqualTo(b);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id IN (?,?)",Long.class,a,b)).isZero();
    }
    @Test void concurrentSubmissionOfSameOperationCreatesOneNotification() throws Exception {
        var raw=prepare().plan();assertThat(raw).isNotNull();var action=new AgentTask();action.setUserId(owner);action.setToolName("sendNotification");action.setStatus("RUNNING");action.setCreatedAt(java.time.LocalDateTime.now());action=tasks.saveAndFlush(action);
        var p=new AgentActionPlan(action.getId(),raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,null,raw.preview());
        var latch=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var jobs=new ArrayList<java.util.concurrent.Future<WriteAgentTool.CommitResult>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{OrgContext.set(1L);try{latch.await();return notifications.commit(ctx,p);}finally{OrgContext.clear();}}));latch.countDown();
            var a=jobs.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS);var b=jobs.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS);assertThat(a.detail()).isEqualTo(b.detail());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE title=?",Long.class,title)).isEqualTo(1L);
    }
}
