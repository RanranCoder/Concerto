package com.pams.module.notification.service;

import com.pams.common.*;
import com.pams.module.notification.dto.*;
import com.pams.module.notification.entity.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class NotificationService {
  @org.springframework.beans.factory.annotation.Autowired
  private NotificationPolicyService policies;
  private final JdbcTemplate db;
  private final NotificationPipeline pipeline;
  private final NotificationVisibility visibility;
  public NotificationService(JdbcTemplate db,NotificationPipeline pipeline,NotificationVisibility visibility) {
    this.db=db;this.pipeline=pipeline;this.visibility=visibility;
  }
  @Transactional
  public Notification createAndSave(NotificationType type,String title,String content,String entity,Long id,
                                    Long sender,Long user,String role,Long dept) {
    return pipeline.record(type,title,content,entity,id,sender,user,role,dept);
  }
  public List<NotificationVO> findForUser(Long uid,String role,Long dept) {
    return pageForUser(uid,role,dept,null,1,50).getRecords();
  }
  public long countUnreadForUser(Long uid,String role,Long dept) {
    return db.queryForObject("SELECT COUNT(*) FROM notification_inbox i JOIN notification n ON n.id=i.message_id AND n.org_id=i.org_id "
      +"WHERE i.org_id=? AND i.user_id=? AND i.read_at IS NULL AND i.archived_at IS NULL AND n.deleted=0",Long.class,NotificationPipeline.org(),uid);
  }
  public PageResult<NotificationVO> pageForUser(Long uid,String role,Long dept,NotificationType type,int page,int size) {
    int current=Math.max(1,page),limit=Math.max(1,Math.min(100,size));
    String where=" FROM notification_inbox i JOIN notification n ON n.id=i.message_id AND n.org_id=i.org_id "
      +"LEFT JOIN sys_user u ON u.id=n.sender_id AND u.org_id=n.org_id AND u.deleted=0 "
      +"WHERE i.org_id=? AND i.user_id=? AND n.deleted=0"+(type==null?"":" AND n.type=?");
    var args=new ArrayList<Object>(List.of(NotificationPipeline.org(),uid));if(type!=null)args.add(type.name());
    PageResult<NotificationVO> result=new PageResult<>();result.setCurrent(current);result.setSize(limit);
    result.setTotal(db.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray()));
    args.add(limit);args.add((long)(current-1)*limit);
    var policy=policies==null?null:policies.get();var now=java.time.LocalDateTime.now(PendingWorkService.ZONE);
    result.setRecords(db.query("SELECT n.*,i.read_at AS personal_read_at,i.silent_at_materialization AS personal_silent,u.real_name AS sender_name"+where
      +" ORDER BY i.created_at DESC,i.id DESC LIMIT ? OFFSET ?",(rs,row)->toVO(rs,uid,policy,now),args.toArray()));
    return result;
  }
  private NotificationVO toVO(ResultSet rs,long uid,NotificationPolicyService.Policy policy,java.time.LocalDateTime now) throws SQLException {
    Notification n=new Notification();n.setId(rs.getLong("id"));n.setOrgId(rs.getLong("org_id"));
    n.setType(NotificationType.valueOf(rs.getString("type")));n.setEntityType(rs.getString("entity_type"));
    n.setEntityId(rs.getObject("entity_id",Long.class));n.setActivityId(rs.getObject("activity_id",Long.class));
    NotificationVO v=new NotificationVO();v.setId(n.getId());v.setType(n.getType().name());
    v.setRead(rs.getTimestamp("personal_read_at")!=null);v.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
    v.setPriority(n.getType().name().contains("CANCEL")?"URGENT":"NORMAL");
    if(!visibility.canRead(uid,n)) {
      v.setTitle("业务通知");v.setContent("当前没有该业务内容的查看权限");return v;
    }
    v.setTitle(rs.getString("title"));v.setContent(rs.getString("content"));v.setSenderName(rs.getString("sender_name"));
    v.setNotify(db.queryForObject("SELECT COUNT(*) FROM notification_preference WHERE org_id=? AND user_id=? AND event_type=? AND enabled=FALSE",Long.class,NotificationPipeline.org(),uid,n.getType().name())==0);
    if(n.getType()==NotificationType.GOVERNANCE_CHANGED)v.setNotify(true);
    if(n.getType()==NotificationType.ANNOUNCEMENT) {
      long immediate=db.queryForObject("SELECT COUNT(*) FROM announcement WHERE org_id=? AND id=? AND deleted=0 AND (important=TRUE OR require_confirmation=TRUE)",Long.class,NotificationPipeline.org(),n.getEntityId());
      if(immediate==0)v.setNotify(false);
    }
    v.setEntityType(n.getEntityType());v.setEntityId(n.getEntityId());v.setActivityId(n.getActivityId());
    if(n.getType()==NotificationType.PLAN_SUBMITTED) {
      var rows=db.queryForList("SELECT p.status,r.approved FROM activity_plan p JOIN activity_plan_review r ON r.plan_id=p.id "
        +"WHERE p.org_id=? AND p.id=? AND r.reviewer_id=?",NotificationPipeline.org(),n.getEntityId(),uid);
      boolean open=!rows.isEmpty() && "PENDING".equals(rows.getFirst().get("status")) && rows.getFirst().get("approved")==null;
      v.setActionState(open?"open":!rows.isEmpty() && ("WITHDRAWN".equals(rows.getFirst().get("status")) || "REJECTED".equals(rows.getFirst().get("status")))?"cancelled":"completed");
    } else if(n.getType()==NotificationType.TASK_ASSIGNED || n.getType()==NotificationType.TASK_CHANGED || n.getType()==NotificationType.TASK_DUE_REMINDER || n.getType()==NotificationType.TASK_OVERDUE_REMINDER) {
      var tasks=db.queryForList("SELECT status,assignee_user_id FROM task WHERE id=? AND org_id=? AND deleted=0",n.getEntityId(),NotificationPipeline.org());
      if(tasks.isEmpty())v.setActionState("cancelled");
      else if("DONE".equals(tasks.getFirst().get("status")))v.setActionState("completed");
      else {
        Object assigned=tasks.getFirst().get("assignee_user_id");
        v.setActionState(assigned==null || ((Number)assigned).longValue()==uid?"open":"cancelled");
      }
    }
    if((n.getType()==NotificationType.PLAN_SUBMITTED || n.getType()==NotificationType.TASK_ASSIGNED) && !"open".equals(v.getActionState())) v.setNotify(false);
    if(Set.of(NotificationType.TASK_DUE_REMINDER,NotificationType.TASK_OVERDUE_REMINDER,NotificationType.TASK_OVERDUE_DIGEST,NotificationType.SCHEDULE_START_REMINDER,NotificationType.ARTICLE_DEADLINE_REMINDER).contains(n.getType())) {
      long current=db.queryForObject("SELECT COUNT(*) FROM notification_reminder r JOIN notification_reminder_source s ON s.org_id=r.org_id AND s.resource_type=r.resource_type AND s.resource_id=r.resource_id "
        +"WHERE r.org_id=? AND r.message_id=? AND r.user_id=? AND r.generation=s.generation AND s.active=TRUE AND (r.expires_at IS NULL OR r.expires_at>CURRENT_TIMESTAMP)",Long.class,NotificationPipeline.org(),n.getId(),uid);
      if(current==0 || "completed".equals(v.getActionState()) || "cancelled".equals(v.getActionState()))v.setNotify(false);
    }
    if(policy!=null && !NotificationPolicyService.allowImmediate(policy,n.getType(),v.getCreatedAt(),now))v.setNotify(false);
    if(rs.getBoolean("personal_silent"))v.setNotify(false);
    return v;
  }
  @Transactional
  public void markAsRead(Long id,Long uid) {
    long org=NotificationPipeline.org();
    if(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE org_id=? AND user_id=? AND message_id=?",Long.class,org,uid,id)==0)
      throw new BizException(2050,"通知不存在");
    if(db.update("UPDATE notification_inbox SET read_at=CURRENT_TIMESTAMP WHERE org_id=? AND user_id=? AND message_id=? AND read_at IS NULL",org,uid,id)>0)
      pipeline.invalidate(uid);
  }
  @Transactional
  public void markAllAsRead(Long uid,String role,Long dept) {
    if(db.update("UPDATE notification_inbox SET read_at=CURRENT_TIMESTAMP WHERE org_id=? AND user_id=? AND read_at IS NULL",NotificationPipeline.org(),uid)>0)
      pipeline.invalidate(uid);
  }
  public List<NotificationPreferenceVO> getPreferences(Long uid) {
    Map<String,Boolean> prefs=new HashMap<>();
    db.query("SELECT event_type,enabled FROM notification_preference WHERE org_id=? AND user_id=?",rs->{prefs.put(rs.getString(1),rs.getBoolean(2));},NotificationPipeline.org(),uid);
    return Arrays.stream(NotificationType.values()).map(t->NotificationPreferenceVO.of(t.name(),t==NotificationType.SYSTEM || t==NotificationType.GOVERNANCE_CHANGED || prefs.getOrDefault(t.name(),true),t==NotificationType.SYSTEM || t==NotificationType.GOVERNANCE_CHANGED)).toList();
  }
  @Transactional
  public void savePreferences(Long uid,List<NotificationPreferenceVO> prefs) {
    if(prefs==null)return;
    for(var p:prefs) {
      if(p==null || p.getType()==null)continue;
      NotificationType t;try{t=NotificationType.valueOf(p.getType());}catch(IllegalArgumentException e){continue;}
      if(t==NotificationType.SYSTEM || t==NotificationType.GOVERNANCE_CHANGED)continue;
      db.update("INSERT INTO notification_preference(org_id,user_id,event_type,enabled) VALUES(?,?,?,?) "
        +"ON DUPLICATE KEY UPDATE enabled=?",NotificationPipeline.org(),uid,t.name(),p.isEnabled(),p.isEnabled());
    }
  }
}
