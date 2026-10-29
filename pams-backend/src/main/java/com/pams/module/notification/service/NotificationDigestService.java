package com.pams.module.notification.service;

import com.pams.module.notification.entity.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDigestService {
  private final JdbcTemplate db;private final NotificationPolicyService policies;private final NotificationVisibility visibility;
  private final NotificationPipeline pipeline;private final TenantAuthorizationService auth;
  public NotificationDigestService(JdbcTemplate db,NotificationPolicyService policies,NotificationVisibility visibility,NotificationPipeline pipeline,TenantAuthorizationService auth){this.db=db;this.policies=policies;this.visibility=visibility;this.pipeline=pipeline;this.auth=auth;}
  @Transactional
  public int dispatch(long uid,LocalDateTime now) {
    long org=NotificationPipeline.org();var policy=policies.get(true);
    if(!policy.digestEnabled() || now.toLocalTime().isBefore(policy.digestTime()) || NotificationPolicyService.quiet(policy,now)
        || !auth.valid(uid) || ((Number)auth.identity(uid).get("org_id")).longValue()!=org)return 0;
    if(db.queryForObject("SELECT COUNT(*) FROM notification_daily_digest WHERE org_id=? AND user_id=? AND digest_day=?",Long.class,org,uid,now.toLocalDate())>0)return 0;
    long previous=db.queryForObject("SELECT COALESCE(MAX(last_inbox_id),0) FROM notification_daily_digest WHERE org_id=? AND user_id=?",Long.class,org,uid);
    long end=db.queryForObject("SELECT COALESCE(MAX(id),0) FROM notification_inbox WHERE org_id=? AND user_id=?",Long.class,org,uid);
    // Calendar-day boundary avoids losing delayed records when the daily scheduler jitters by seconds.
    LocalDateTime earliest=now.toLocalDate().minusDays(1).atStartOfDay();
    LocalDateTime since=policy.digestSince().isAfter(earliest)?policy.digestSince():earliest;
    List<Long> sources=new ArrayList<>();Map<String,Integer> counts=new LinkedHashMap<>();
    for(var row:db.queryForList("SELECT n.* FROM notification_inbox i JOIN notification n ON n.id=i.message_id AND n.org_id=i.org_id WHERE i.org_id=? AND i.user_id=? AND i.id>? AND i.id<=? AND i.read_at IS NULL AND i.archived_at IS NULL AND n.deleted=0 AND i.created_at>=? AND i.created_at<=? AND NOT EXISTS(SELECT 1 FROM notification_preference p WHERE p.org_id=i.org_id AND p.user_id=i.user_id AND p.event_type=n.type AND p.enabled=FALSE) ORDER BY i.id",org,uid,previous,end,since,now)) {
      NotificationType type=NotificationType.valueOf((String)row.get("type"));
      boolean ordinaryAnnouncement=type==NotificationType.ANNOUNCEMENT && db.queryForObject("SELECT COUNT(*) FROM announcement WHERE org_id=? AND id=? AND deleted=0 AND important=FALSE AND require_confirmation=FALSE",Long.class,org,row.get("entity_id"))>0;
      if(!NotificationPolicyService.INFORMATION.contains(type) && !ordinaryAnnouncement)continue;
      var n=new Notification();n.setId(((Number)row.get("id")).longValue());n.setOrgId(org);n.setType(type);n.setEntityType((String)row.get("entity_type"));n.setEntityId(row.get("entity_id")==null?null:((Number)row.get("entity_id")).longValue());
      if(!visibility.canRead(uid,n))continue;
      sources.add(n.getId());counts.merge(type.name(),1,Integer::sum);
    }
    Long message=null;
    if(!sources.isEmpty() && db.queryForObject("SELECT COUNT(*) FROM notification_preference WHERE org_id=? AND user_id=? AND event_type='ORG_DIGEST' AND enabled=FALSE",Long.class,org,uid)==0) {
      var notice=pipeline.record(NotificationType.ORG_DIGEST,"我的每日资讯摘要",now.toLocalDate()+"：汇总 "+sources.size()+" 条本人可见的未读资讯（"+String.join("，",counts.entrySet().stream().map(e->label(e.getKey())+" "+e.getValue()+" 条").toList())+"）。请在通知中心查看原消息。","ORG_DIGEST",uid,null,uid,null,null);
      message=notice.getId();
      for(long source:sources)db.update("INSERT INTO notification_digest_item(org_id,digest_message_id,user_id,source_message_id) VALUES(?,?,?,?)",org,message,uid,source);
    }
    db.update("INSERT INTO notification_daily_digest(org_id,user_id,digest_day,last_inbox_id,item_count,message_id,created_at) VALUES(?,?,?,?,?,?,?)",org,uid,now.toLocalDate(),end,sources.size(),message,now);
    return message==null?0:1;
  }
  private String label(String type){return switch(type){case "ANNOUNCEMENT"->"普通公告";case "NEWS_UPLOADED"->"新媒体";case "ARTICLE_PUBLISHED"->"推文发布";case "QUALITY_SUMMARY"->"素拓摘要";case "PARTY_SUMMARY"->"党务摘要";case "IMPORT_RESULT"->"导入结果";default->"维护告警";};}
}
