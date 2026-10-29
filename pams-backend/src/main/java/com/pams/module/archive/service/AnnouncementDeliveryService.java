package com.pams.module.archive.service;

import com.pams.common.*;
import com.pams.module.archive.dto.AnnouncementRequest;
import com.pams.module.archive.entity.Announcement;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.service.NotificationPipeline;
import com.pams.module.permission.service.TenantAuthorizationService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnouncementDeliveryService {
  private final JdbcTemplate db;private final TenantAuthorizationService auth;private final NotificationPipeline pipeline;
  public AnnouncementDeliveryService(JdbcTemplate db,TenantAuthorizationService auth,NotificationPipeline pipeline){this.db=db;this.auth=auth;this.pipeline=pipeline;}
  @Transactional
  public void publish(Announcement a,AnnouncementRequest req,Long sender) {
    long org=NotificationPipeline.org();
    Set<Long> selected=new LinkedHashSet<>(Objects.requireNonNullElse(req.getAudienceUserIds(),List.of()));
    var departments=Objects.requireNonNullElse(req.getAudienceDeptIds(),List.<Long>of());
    if(!req.isAudienceAll() && selected.isEmpty() && departments.isEmpty())throw new BizException(400,"请选择公告通知受众");
    for(Long uid:selected)if(uid==null || db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE org_id=? AND id=? AND status=1 AND deleted=0",Long.class,org,uid)==0)throw new BizException(400,"公告受众包含无效账号");
    for(Long dept:departments)if(dept==null || db.queryForObject("SELECT COUNT(*) FROM sys_department WHERE org_id=? AND id=?",Long.class,org,dept)==0)throw new BizException(400,"公告受众包含无效部门");
    for(var row:db.queryForList("SELECT id,dept_id FROM sys_user WHERE org_id=? AND status=1 AND deleted=0",org)) {
      long uid=((Number)row.get("id")).longValue();
      Long dept=row.get("dept_id")==null?null:((Number)row.get("dept_id")).longValue();
      if(Objects.equals(sender,uid) || !(req.isAudienceAll() || selected.contains(uid) || (dept!=null && departments.contains(dept))) || !auth.business(uid).contains("notice:view"))continue;
      if(a.isRequireConfirmation())db.update("INSERT INTO announcement_receipt(org_id,announcement_id,user_id,version) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE user_id=?",org,a.getId(),uid,a.getConfirmationVersion(),uid);
      pipeline.record(NotificationType.ANNOUNCEMENT,req.isImportantCorrection()?"公告重要更正":"新公告",
          "《"+a.getTitle()+"》"+(a.isRequireConfirmation()?"需要你单独确认，请查看公告。":"已发布，请查看公告。"),"ANNOUNCEMENT",a.getId(),sender,uid,null,null);
      pipeline.invalidate(uid);
    }
  }
  public List<Map<String,Object>> receipts(long id,long uid) {
    long org=NotificationPipeline.org();requireReader(uid,org);
    // Only one's own confirmation state is exposed by this endpoint.
    return db.queryForList("SELECT r.version,r.confirmed_at FROM announcement_receipt r JOIN announcement a ON a.id=r.announcement_id AND a.org_id=r.org_id WHERE r.org_id=? AND r.announcement_id=? AND r.user_id=? AND a.deleted=0 AND a.require_confirmation=TRUE AND r.version=a.confirmation_version",org,id,uid);
  }
  private void requireReader(long uid,long org) {
    if(!auth.valid(uid) || ((Number)auth.identity(uid).get("org_id")).longValue()!=org || !auth.business(uid).contains("notice:view"))throw new BizException(403,"没有公告访问权限");
  }
  @Transactional
  public void confirm(long id,int version,long uid) {
    long org=NotificationPipeline.org();requireReader(uid,org);
    var current=db.queryForList("SELECT confirmation_version FROM announcement WHERE org_id=? AND id=? AND deleted=0 AND require_confirmation=TRUE FOR UPDATE",org,id);
    if(current.isEmpty() || ((Number)current.getFirst().get("confirmation_version")).intValue()!=version)throw new BizException(409,"公告已变更，请重新查看后确认");
    if(db.queryForObject("SELECT COUNT(*) FROM announcement_receipt WHERE org_id=? AND announcement_id=? AND user_id=? AND version=?",Long.class,org,id,uid,version)==0)throw new BizException(403,"不在本轮确认受众中");
    db.update("UPDATE announcement_receipt SET confirmed_at=COALESCE(confirmed_at,CURRENT_TIMESTAMP) WHERE org_id=? AND announcement_id=? AND user_id=? AND version=?",org,id,uid,version);
    pipeline.invalidate(uid);
  }
}
