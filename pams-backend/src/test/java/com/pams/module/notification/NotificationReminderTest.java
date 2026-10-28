package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import com.pams.common.OrgContext;
import com.pams.module.notification.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class NotificationReminderTest {
  @Autowired JdbcTemplate db;
  @Autowired NotificationReminderService reminders;
  @Autowired NotificationReminderScheduler scheduler;
  @Autowired NotificationOutboxWorker outbox;
  @Autowired PlatformTransactionManager transactions;
  @Autowired com.pams.module.routine.service.RoutineService routine;
  @Autowired com.pams.module.routine.service.ActivityScheduleService publication;
  long user,other,activity;
  final LocalDateTime now=LocalDateTime.of(2026,10,28,10,0);
  final List<Long> tasks=new ArrayList<>(),schedules=new ArrayList<>();
  @BeforeEach void setup(){
    OrgContext.set(1L);
    user=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);
    other=db.queryForObject("SELECT id FROM sys_user WHERE username='staff'",Long.class);
    String name="提醒测试-"+UUID.randomUUID();
    db.update("INSERT INTO activity(name,status,org_id,deleted,created_at,updated_at) VALUES(?,'EXECUTING',1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",name);
    activity=db.queryForObject("SELECT id FROM activity WHERE name=?",Long.class,name);
  }
  @AfterEach void cleanup(){
    for(long id:tasks)db.update("UPDATE task SET deleted=1 WHERE id=?",id);
    for(long id:schedules)db.update("UPDATE schedule SET status='CANCELLED' WHERE id=?",id);
    db.update("UPDATE activity SET status='ARCHIVED' WHERE id=?",activity);OrgContext.clear();
  }
  long task(LocalDate due){
    String name="提醒任务-"+UUID.randomUUID();
    db.update("INSERT INTO task(activity_id,name,assignee,assignee_user_id,end_date,progress,status,org_id,deleted,revision,created_at,updated_at) VALUES(?,?,?, ?,?,0,'TODO',1,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",activity,name,"测试负责人",user,due);
    long id=db.queryForObject("SELECT id FROM task WHERE activity_id=? AND name=?",Long.class,activity,name);tasks.add(id);return id;
  }
  long reminder(String type,long id,String rule){return db.queryForObject("SELECT MAX(id) FROM notification_reminder WHERE org_id=1 AND resource_type=? AND resource_id=? AND rule_key=?",Long.class,type,id,rule);}
  String status(long id){return db.queryForObject("SELECT status FROM notification_reminder WHERE id=?",String.class,id);}
  @Test void dueReminderIsDurableAndIdempotent(){
    long id=task(now.toLocalDate().plusDays(1));reminders.reconcile("TASK",id,now);
    long notice=reminder("TASK",id,"TASK_DUE");reminders.dispatch(notice,now);
    long message=db.queryForObject("SELECT message_id FROM notification_reminder WHERE id=?",Long.class,notice);
    reminders.dispatch(notice,now);outbox.tick();
    assertThat(status(notice)).isEqualTo("SENT");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id=? AND user_id=?",Long.class,message,user)).isEqualTo(1);
  }
  @Test void reassignAndRescheduleCancelTheOldGeneration(){
    long id=task(now.toLocalDate().plusDays(1));reminders.reconcile("TASK",id,now);long old=reminder("TASK",id,"TASK_DUE");
    db.update("UPDATE task SET assignee_user_id=?,end_date=? WHERE id=?",other,now.toLocalDate().plusDays(5),id);
    reminders.reconcile("TASK",id,now);reminders.dispatch(old,now);
    assertThat(status(old)).isEqualTo("CANCELLED");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_reminder WHERE resource_type='TASK' AND resource_id=? AND status='PENDING' AND user_id=?",Long.class,id,other)).isEqualTo(2);
  }
  @Test void completionAndReopenNeverReviveTheOldReminder(){
    long id=task(now.toLocalDate().minusDays(1));reminders.reconcile("TASK",id,now);long old=reminder("TASK",id,"TASK_OVERDUE");
    db.update("UPDATE task SET progress=100,status='DONE' WHERE id=?",id);reminders.reconcile("TASK",id,now);
    db.update("UPDATE task SET progress=0,status='TODO' WHERE id=?",id);reminders.reconcile("TASK",id,now);
    long fresh=reminder("TASK",id,"TASK_OVERDUE");
    assertThat(fresh).isNotEqualTo(old);assertThat(status(old)).isEqualTo("CANCELLED");
    reminders.dispatch(fresh,now);assertThat(status(fresh)).isEqualTo("SENT");
  }
  @Test void cancelledScheduleIsRejectedAtDispatchEvenWithoutAReconciliationTick(){
    db.update("INSERT INTO schedule(schedule_type,activity_id,status,source,org_id,schedule_date,start_time,end_time,created_at,updated_at) VALUES('ACTIVITY',?,'PUBLISHED','MANUAL',1,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",activity,now.toLocalDate(),now.plusMinutes(20).toLocalTime(),now.plusHours(1).toLocalTime());
    long id=db.queryForObject("SELECT MAX(id) FROM schedule WHERE activity_id=?",Long.class,activity);schedules.add(id);
    db.update("INSERT INTO schedule_person(schedule_id,user_id,person_name,is_primary,org_id) VALUES(?,?,?,1,1)",id,user,"测试负责人");
    reminders.reconcile("SCHEDULE",id,now);long due=reminder("SCHEDULE",id,"SCHEDULE_START");
    db.update("UPDATE schedule SET status='CANCELLED' WHERE id=?",id);reminders.dispatch(due,now);
    assertThat(status(due)).isEqualTo("CANCELLED");
  }
  @Test void restartCatchupSkipsExpiredDueNoticeButSendsOneOverdueNotice(){
    long id=task(now.toLocalDate().minusDays(1));scheduler.tick(now);scheduler.tick(now.plusMinutes(1));
    assertThat(status(reminder("TASK",id,"TASK_DUE"))).isEqualTo("CANCELLED");
    assertThat(status(reminder("TASK",id,"TASK_OVERDUE"))).isEqualTo("SENT");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE entity_type='TASK' AND entity_id=? AND type='TASK_OVERDUE_REMINDER'",Long.class,id)).isEqualTo(1);
  }
  @Test void overdueDigestBeginsOnTheNextWorkdayAndIsOnlySentOncePerDay(){
    task(now.toLocalDate().minusDays(2));scheduler.tick(now);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_reminder WHERE resource_type='TASK_DIGEST' AND resource_id=? AND window_key=? AND status='SENT'",Long.class,user,now.toLocalDate().toString())).isZero();
    scheduler.tick(now.plusDays(1));scheduler.tick(now.plusDays(1).plusMinutes(2));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_reminder WHERE resource_type='TASK_DIGEST' AND resource_id=? AND window_key=? AND status='SENT'",Long.class,user,now.toLocalDate().plusDays(1).toString())).isEqualTo(1);
  }
  @Test void reminderIntentAndSentStateRollBackTogether(){
    long id=task(now.toLocalDate().plusDays(1));reminders.reconcile("TASK",id,now);long due=reminder("TASK",id,"TASK_DUE");
    long before=db.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class);
    new TransactionTemplate(transactions).executeWithoutResult(s->{reminders.dispatch(due,now);s.setRollbackOnly();});
    assertThat(status(due)).isEqualTo("PENDING");assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class)).isEqualTo(before);
    reminders.dispatch(due,now);assertThat(status(due)).isEqualTo("SENT");
  }
  @Test void anotherTenantCannotConsumeTheReminder(){
    long id=task(now.toLocalDate().plusDays(1));reminders.reconcile("TASK",id,now);long due=reminder("TASK",id,"TASK_DUE");
    OrgContext.set(2L);try{reminders.dispatch(due,now);assertThat(status(due)).isEqualTo("PENDING");}finally{OrgContext.set(1L);}
  }
  @Test void submittedArticleStopsAuthorRemindersAndRejectionStartsANewRound(){
    String title="推文提醒-"+UUID.randomUUID();
    db.update("INSERT INTO article(title,content,activity_id,status,author_id,deadline,org_id,deleted) VALUES(?,'测试正文',?,'DRAFT',?,?,1,0)",title,activity,user,now.plusDays(1));
    long id=db.queryForObject("SELECT id FROM article WHERE title=?",Long.class,title);
    reminders.reconcile("ARTICLE",id,now);long old=reminder("ARTICLE",id,"ARTICLE_DUE");
    db.update("UPDATE article SET status='PENDING' WHERE id=?",id);reminders.reconcile("ARTICLE",id,now);
    reminders.dispatch(old,now);assertThat(status(old)).isEqualTo("CANCELLED");
    db.update("UPDATE article SET status='REJECTED' WHERE id=?",id);reminders.reconcile("ARTICLE",id,now);
    long fresh=reminder("ARTICLE",id,"ARTICLE_DUE");assertThat(fresh).isNotEqualTo(old);
    reminders.dispatch(fresh,now);assertThat(status(fresh)).isEqualTo("SENT");
  }
  @Test void concurrentWorkersOnlyProduceOneIntent() throws Exception {
    long id=task(now.toLocalDate().plusDays(1));reminders.reconcile("TASK",id,now);long due=reminder("TASK",id,"TASK_DUE");
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start=new java.util.concurrent.CountDownLatch(1);
      var run=(java.util.concurrent.Callable<Void>)()->{OrgContext.set(1L);try{start.await();reminders.dispatch(due,now);return null;}finally{OrgContext.clear();}};
      var a=executor.submit(run);var b=executor.submit(run);start.countDown();a.get(15,java.util.concurrent.TimeUnit.SECONDS);b.get(15,java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE entity_type='TASK' AND entity_id=? AND type='TASK_DUE_REMINDER'",Long.class,id)).isEqualTo(1);
  }
  @Test void regularSchedulePublicationNotifiesWithoutInventingAStartTime(){
    var request=new com.pams.module.routine.dto.ScheduleRequest();request.setScheduleType("CLASS_DUTY");
    request.setScheduleDate(now.toLocalDate());request.setSessionName("上午");
    var person=new com.pams.module.routine.dto.ScheduleRequest.SchedulePersonItem();person.setUserId(user);person.setPersonName("测试负责人");person.setIsPrimary(1);request.setPersons(List.of(person));
    long id=routine.createSchedule(request);schedules.add(id);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE entity_type='SCHEDULE' AND entity_id=?",Long.class,id)).isZero();
    publication.publish(id);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE entity_type='SCHEDULE' AND entity_id=? AND type='SCHEDULE_PUBLISHED'",Long.class,id)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_reminder WHERE resource_type='SCHEDULE' AND resource_id=?",Long.class,id)).isZero();
    publication.cancel(id);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE entity_type='SCHEDULE' AND entity_id=? AND type='SCHEDULE_CANCELLED'",Long.class,id)).isEqualTo(1);
  }
}
