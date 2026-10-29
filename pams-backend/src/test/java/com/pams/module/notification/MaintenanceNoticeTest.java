package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import com.pams.common.OrgContext;
import com.pams.module.notification.service.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.module.notification.repository.NotificationRepository;
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
class MaintenanceNoticeTest {
  @Autowired JdbcTemplate db;
  @Autowired MaintenanceNoticeService maintenance;
  @Autowired MaintenanceNoticeScheduler scheduler;
  @Autowired NotificationVisibility visibility;
  @Autowired NotificationRepository messages;
  @Autowired PlatformTransactionManager manager;
  long owner,staff;LocalDateTime now;List<Long> ids=new ArrayList<>();
  @BeforeEach void setup(){OrgContext.set(1L);now=LocalDateTime.now(PendingWorkService.ZONE);owner=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);staff=db.queryForObject("SELECT id FROM sys_user WHERE username='staff'",Long.class);db.update("DELETE FROM notification_maintenance_subscription WHERE org_id=1 AND user_id=?",owner);}
  @AfterEach void clear(){db.update("DELETE FROM notification_maintenance_subscription WHERE org_id=1 AND user_id=?",owner);for(long id:ids){db.update("DELETE FROM notification_incident_delivery WHERE incident_id=?",id);db.update("DELETE FROM notification_incident WHERE id=?",id);}OrgContext.clear();}
  long incident(long org,String category,LocalDate day,LocalDateTime seen) {
    db.update("INSERT INTO notification_incident(org_id,category,window_date,occurrences,first_seen_at,last_seen_at) VALUES(?,?,?,3,?,?)",org,category,day,seen,seen);
    long id=db.queryForObject("SELECT id FROM notification_incident WHERE org_id=? AND category=? AND window_date=?",Long.class,org,category,day);ids.add(id);return id;
  }
  @Test void defaultsOffAndOnlyExplicitSubscribersReceiveOneDailySummary() {
    long id=incident(1,"KB_INDEX_FAILURE",now.toLocalDate(),now.plusSeconds(2));
    assertThat(maintenance.subscription(owner).enabled()).isFalse();assertThat(maintenance.dispatch(owner,now.plusMinutes(1))).isZero();
    maintenance.subscribe(owner,true);
    assertThat(maintenance.dispatch(owner,now.plusMinutes(1))).isEqualTo(1);
    db.update("UPDATE notification_incident SET occurrences=50,last_seen_at=? WHERE id=?",now.plusMinutes(2),id);
    assertThat(maintenance.dispatch(owner,now.plusMinutes(3))).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_incident_delivery WHERE incident_id=?",Long.class,id)).isEqualTo(1);
    long message=db.queryForObject("SELECT message_id FROM notification_incident_delivery WHERE incident_id=?",Long.class,id);
    assertThat(visibility.canRead(owner,messages.findById(message).orElseThrow())).isTrue();
    assertThat(visibility.canRead(staff,messages.findById(message).orElseThrow())).isFalse();
  }
  @Test void disableAndSubscriptionBoundaryPreventHistoricalFlooding() {
    incident(1,"KB_DAILY_REBUILD_FAILURE",now.toLocalDate(),now.minusHours(1));
    maintenance.subscribe(owner,true);assertThat(maintenance.dispatch(owner,now.plusMinutes(1))).isZero();
    maintenance.subscribe(owner,false);long id=incident(1,"KB_QUEUE_REJECTED",now.toLocalDate(),now.plusMinutes(1));
    assertThat(maintenance.dispatch(owner,now.plusMinutes(2))).isZero();
    maintenance.subscribe(owner,true);assertThat(maintenance.dispatch(owner,now.plusMinutes(2))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_incident_delivery WHERE incident_id=?",Long.class,id)).isEqualTo(1);
  }
  @Test void authorizationAndOrganizationAreRechecked() {
    assertThatThrownBy(()->maintenance.subscribe(staff,true)).hasMessageContaining("审计");
    maintenance.subscribe(owner,true);incident(2,"KB_INDEX_FAILURE",now.toLocalDate(),now.plusMinutes(1));
    assertThat(maintenance.dispatch(owner,now.plusMinutes(2))).isZero();
    OrgContext.set(2L);assertThatThrownBy(()->maintenance.subscribe(owner,true)).hasMessageContaining("审计");
    assertThat(maintenance.dispatch(owner,now.plusMinutes(2))).isZero();
    OrgContext.set(1L);
    incident(1,"KB_INDEX_FAILURE",now.toLocalDate(),now.plusMinutes(1));
    var tx=new TransactionTemplate(manager);tx.executeWithoutResult(s->{
      db.update("UPDATE organization SET owner_user_id=? WHERE id=1",staff);
      assertThat(maintenance.dispatch(owner,now.plusMinutes(2))).isZero();s.setRollbackOnly();
    });
  }
  @Test void factAndDeliveryReceiptRollbackTogetherAndSchedulerRestoresContext() {
    maintenance.subscribe(owner,true);long id=incident(1,"KB_INDEX_FAILURE",now.toLocalDate(),now.plusMinutes(1));
    var tx=new TransactionTemplate(manager);tx.executeWithoutResult(s->{assertThat(maintenance.dispatch(owner,now.plusMinutes(2))).isEqualTo(1);s.setRollbackOnly();});
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_incident_delivery WHERE incident_id=?",Long.class,id)).isZero();
    OrgContext.set(99L);scheduler.tick(now.plusMinutes(2));assertThat(OrgContext.get()).isEqualTo(99L);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_incident_delivery WHERE incident_id=?",Long.class,id)).isEqualTo(1);
    OrgContext.set(1L);
  }
  @Test void concurrentScansSendOnceAndANewDayHasItsOwnSummary() throws Exception {
    var boundary=maintenance.subscribe(owner,true).enabledAt();
    assertThat(maintenance.subscribe(owner,true).enabledAt()).isEqualTo(boundary);
    long id=incident(1,"KB_QUEUE_REJECTED",now.toLocalDate(),now.plusMinutes(1));
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Integer> dispatch=()->{OrgContext.set(1L);try{return maintenance.dispatch(owner,now.plusMinutes(2));}finally{OrgContext.clear();}};
      var results=pool.invokeAll(List.of(dispatch,dispatch));assertThat(results.get(0).get()+results.get(1).get()).isEqualTo(1);
    }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_incident_delivery WHERE incident_id=?",Long.class,id)).isEqualTo(1);
    incident(1,"KB_QUEUE_REJECTED",now.toLocalDate().plusDays(1),now.plusDays(1));
    assertThat(maintenance.dispatch(owner,now.plusDays(1).plusMinutes(1))).isEqualTo(1);
  }
}
