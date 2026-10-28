package com.pams.module.notification.service;

import com.pams.common.OrgContext;
import com.pams.module.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationOutboxWorker {
  private static final Logger log=LoggerFactory.getLogger(NotificationOutboxWorker.class);
  private final JdbcTemplate db;private final NotificationRepository messages;
  private final NotificationVisibility visibility;private final NotificationStreamRegistry streams;
  private final TransactionTemplate tx;private final Environment environment;
  public NotificationOutboxWorker(JdbcTemplate db,NotificationRepository messages,NotificationVisibility visibility,
      NotificationStreamRegistry streams,PlatformTransactionManager manager,Environment environment) {
    this.db=db;this.messages=messages;this.visibility=visibility;this.streams=streams;this.environment=environment;
    tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }
  @Scheduled(fixedDelayString="${pams.notification.poll-ms:1000}",initialDelay=3000)
  public void scheduled(){if(!environment.matchesProfiles("test"))tick();}
  public synchronized void tick(){process("notification_outbox",false);process("notification_delivery",true);}
  private void process(String table,boolean delivery) {
    // Deliberate cross-tenant metadata scan. Business reads start in a new tenant transaction below.
    var rows=db.queryForList("SELECT id,org_id,attempts FROM "+table+" WHERE (status='PENDING' OR status='PROCESSING') "
      +"AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY id LIMIT 50");
    for(var row:rows) {
      long id=((Number)row.get("id")).longValue(),org=((Number)row.get("org_id")).longValue();
      String lease=UUID.randomUUID().toString();
      int claimed=tx.execute(s->db.update("UPDATE "+table+" SET status='PROCESSING',lease_token=?,lease_until=?,attempts=attempts+1 "
        +"WHERE id=? AND org_id=? AND status IN ('PENDING','PROCESSING') AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)",lease,LocalDateTime.now().plusSeconds(60),id,org));
      if(claimed==0)continue;
      Long previous=OrgContext.get();OrgContext.set(org);
      try {
        if(delivery)deliver(id,org,lease);
        else tx.executeWithoutResult(s->materialize(id,org,lease));
      } catch(Exception e) {
        log.warn("通知处理失败，保留重试 table={} id={} error={}",table,id,e.getClass().getSimpleName());
        int attempt=((Number)row.get("attempts")).intValue()+1;
        long[] delays={5,30,120,600,1800};
        tx.executeWithoutResult(s->db.update("UPDATE "+table+" SET status=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,last_error=? WHERE id=? AND org_id=? AND lease_token=?",
          attempt>=6?"FAILED":"PENDING",LocalDateTime.now().plusSeconds(delays[Math.min(attempt-1,4)]),e.getClass().getSimpleName(),id,org,lease));
      } finally {if(previous==null)OrgContext.clear();else OrgContext.set(previous);}
    }
  }
  private void materialize(long id,long org,String lease) {
    var rows=db.queryForList("SELECT message_id FROM notification_outbox WHERE id=? AND org_id=? AND lease_token=? FOR UPDATE",id,org,lease);
    if(rows.isEmpty())return;
    long message=((Number)rows.getFirst().get("message_id")).longValue();
    var n=messages.findById(message).orElseThrow();
    if(!Objects.equals(n.getOrgId(),org))throw new IllegalStateException("notification tenant mismatch");
    for(Long uid:db.queryForList("SELECT r.user_id FROM notification_outbox_recipient r JOIN sys_user u ON u.id=r.user_id "
        +"WHERE r.outbox_id=? AND u.org_id=? AND u.deleted=0 AND u.status=1",Long.class,id,org)) {
      if(!visibility.canRead(uid,n))continue;
      db.update("INSERT INTO notification_inbox(org_id,message_id,user_id,created_at) VALUES(?,?,?,?) "
        +"ON DUPLICATE KEY UPDATE user_id=?",org,message,uid,n.getCreatedAt(),uid);
      db.update("INSERT INTO notification_delivery(org_id,message_id,user_id,status,next_attempt_at,created_at) VALUES(?,?,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
        +"ON DUPLICATE KEY UPDATE user_id=?",org,message,uid,uid);
    }
    db.update("UPDATE notification_outbox SET status='COMPLETED',lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND org_id=? AND lease_token=?",id,org,lease);
  }
  private void deliver(long id,long org,String lease) {
    var rows=db.queryForList("SELECT user_id FROM notification_delivery WHERE id=? AND org_id=? AND lease_token=?",id,org,lease);
    if(rows.isEmpty())return;
    long uid=((Number)rows.getFirst().get("user_id")).longValue();
    // Network writes occur outside a database transaction. A failed/disconnected stream recovers through REST.
    String status=streams.changed(org,uid)?"SIGNAL_QUEUED":"OFFLINE_SKIPPED";
    tx.executeWithoutResult(s->db.update("UPDATE notification_delivery SET status=?,lease_token=NULL,lease_until=NULL WHERE id=? AND org_id=? AND lease_token=?",status,id,org,lease));
  }
}
