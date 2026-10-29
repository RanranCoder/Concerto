package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import com.pams.common.OrgContext;
import com.pams.module.notification.service.*;
import com.pams.module.notification.service.BusinessNoticeService.*;
import com.pams.module.archive.service.CreditService;
import com.pams.module.archive.dto.CreditRequest;
import com.pams.module.party.service.PartyRecordService;
import com.pams.module.party.dto.PartyRosterRequest;
import com.pams.module.permission.service.TenantAuthorizationService;
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
class BusinessNoticeTest {
  @Autowired JdbcTemplate db;
  @Autowired BusinessNoticeService notices;
  @Autowired PlatformTransactionManager manager;
  @Autowired CreditService credits;
  @Autowired PartyRecordService party;
  @Autowired TenantAuthorizationService auth;
  @Autowired NotificationIncidentService incidents;
  TransactionTemplate tx;long owner;
  final List<Long> creditIds=new ArrayList<>(),rosterIds=new ArrayList<>();
  final List<Long> incidentIds=new ArrayList<>();
  @BeforeEach void setup(){OrgContext.set(1L);org.springframework.security.core.context.SecurityContextHolder.clearContext();tx=new TransactionTemplate(manager);owner=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);}
  @AfterEach void clear(){for(long id:creditIds)db.update("DELETE FROM credit_record WHERE org_id=1 AND id=?",id);for(long id:rosterIds)db.update("DELETE FROM party_roster WHERE org_id=1 AND id=?",id);for(long id:incidentIds)db.update("DELETE FROM notification_incident WHERE id=?",id);OrgContext.clear();}
  long count(){return db.queryForObject("SELECT COUNT(*) FROM notification WHERE org_id=1",Long.class);}
  @Test void hundredsOfChangesProduceOneFrozenSummaryWithQualifiedAudience() {
    long before=count();
    tx.executeWithoutResult(s->{for(int i=0;i<300;i++)notices.add(Domain.QUALITY,Action.ADD,1,owner);notices.add(Domain.QUALITY,Action.UPDATE,7,owner);});
    assertThat(count()).isEqualTo(before+1);
    var row=db.queryForMap("SELECT * FROM notification WHERE org_id=1 ORDER BY id DESC LIMIT 1");
    assertThat(row.get("content").toString()).contains("新增 300 项","调整 7 项");
    var recipients=db.queryForList("SELECT r.user_id FROM notification_outbox_recipient r JOIN notification_outbox o ON o.id=r.outbox_id WHERE o.message_id=?",Long.class,row.get("id"));
    assertThat(recipients).isNotEmpty();
    for(long uid:recipients)assertThat(auth.business(uid)).contains("quality:view","quality:add");
  }
  @Test void rollbackAndEmptyOperationsDoNotEmit() {
    long before=count();
    tx.executeWithoutResult(s->{notices.add(Domain.QUALITY,Action.ADD,10,owner);s.setRollbackOnly();});
    tx.executeWithoutResult(s->notices.add(Domain.QUALITY,Action.ADD,0,owner));
    assertThat(count()).isEqualTo(before);
  }
  @Test void independentTransactionDoesNotMergeIntoRolledBackOuterWork() {
    long before=count();var independent=new TransactionTemplate(manager);
    independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.executeWithoutResult(s->{
      notices.add(Domain.QUALITY,Action.ADD,10,owner);
      independent.executeWithoutResult(inner->notices.add(Domain.QUALITY,Action.UPDATE,2,owner));
      s.setRollbackOnly();
    });
    assertThat(count()).isEqualTo(before+1);
    assertThat(db.queryForObject("SELECT content FROM notification ORDER BY id DESC LIMIT 1",String.class)).contains("调整 2 项").doesNotContain("新增 10");
  }
  @Test void importResultGoesOnlyToItsOperator() {
    tx.executeWithoutResult(s->{notices.add(Domain.MEMBER_IMPORT,Action.IMPORTED,15,owner);notices.add(Domain.MEMBER_IMPORT,Action.FAILED,2,owner);});
    long message=db.queryForObject("SELECT MAX(id) FROM notification WHERE entity_type='MEMBER_IMPORT'",Long.class);
    assertThat(db.queryForList("SELECT r.user_id FROM notification_outbox_recipient r JOIN notification_outbox o ON o.id=r.outbox_id WHERE o.message_id=?",Long.class,message)).containsExactly(owner);
  }
  @Test void realBusinessHooksKeepSensitiveDetailsOutOfNotifications() {
    var req=new CreditRequest();req.setPersonName("隐私姓名甲");req.setStudentNo("私密学号");req.setProject("敏感项目");req.setCredit(new java.math.BigDecimal("1.25"));
    tx.executeWithoutResult(s->creditIds.add(credits.create(owner,req).getId()));
    var roster=new PartyRosterRequest();roster.setRosterType("ACTIVE");roster.setName("隐私姓名乙");roster.setRemark("家庭与考察详情");
    tx.executeWithoutResult(s->rosterIds.add(party.createRoster(roster)));
    assertThat(db.queryForList("SELECT content FROM notification WHERE type IN ('QUALITY_SUMMARY','PARTY_SUMMARY')",String.class)).allSatisfy(c->assertThat(c).doesNotContain("隐私姓名","私密学号","敏感项目","家庭与考察详情"));
  }
  @Test void incidentAggregationSurvivesBusinessRollbackWithoutUsingTheOutbox() {
    long before=count();long initial=db.queryForObject("SELECT COALESCE(SUM(occurrences),0) FROM notification_incident WHERE org_id=1 AND category='KB_INDEX_FAILURE'",Long.class);
    tx.executeWithoutResult(s->{incidents.record(1,NotificationIncidentService.Category.KB_INDEX_FAILURE);incidents.record(1,NotificationIncidentService.Category.KB_INDEX_FAILURE);s.setRollbackOnly();});
    assertThat(db.queryForObject("SELECT SUM(occurrences) FROM notification_incident WHERE org_id=1 AND category='KB_INDEX_FAILURE'",Long.class)).isEqualTo(initial+2);
    assertThat(count()).isEqualTo(before);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_incident WHERE org_id=2 AND category='KB_INDEX_FAILURE'",Long.class)).isZero();
    if(initial==0)incidentIds.add(db.queryForObject("SELECT id FROM notification_incident WHERE org_id=1 AND category='KB_INDEX_FAILURE'",Long.class));
  }
}
