package com.pams.module.notification.service;

import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.dto.TaskRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.module.notification.entity.NotificationType;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business relation snapshots are resolved before the enclosing transaction commits. */
@Service
public class NotificationBusinessEvents {
  private final SchedulePersonRepository people;
  private final NotificationService notifications;
  private final NotificationPipeline pipeline;
  private final JdbcTemplate db;
  private final TenantAuthorizationService auth;
  public record ScheduleSnapshot(Long id,Long activity,String status,String details,Set<Long> users) {}
  public NotificationBusinessEvents(SchedulePersonRepository people,NotificationService notifications,NotificationPipeline pipeline,JdbcTemplate db,TenantAuthorizationService auth) {
    this.people=people;this.notifications=notifications;this.pipeline=pipeline;this.db=db;this.auth=auth;
  }
  public String taskAssignee(long uid,Long dept) {
    var names=db.queryForList("SELECT real_name FROM sys_user WHERE id=? AND org_id=? AND dept_id=? AND status=1 AND deleted=0",String.class,uid,NotificationPipeline.org(),dept);
    if(names.isEmpty())throw new com.pams.common.BizException(2007,"负责人不存在、已停用或不属于负责部门");
    return names.getFirst();
  }
  @Transactional
  public void taskChanged(TaskRequest before,Task after) {
    boolean deleted=Objects.equals(after.getDeleted(),1);
    if(before!=null && !deleted && Objects.equals(before.getName(),after.getName())
      && Objects.equals(before.getAssigneeUserId(),after.getAssigneeUserId()) && Objects.equals(before.getDeptId(),after.getDeptId())
      && Objects.equals(before.getStartDate(),after.getStartDate()) && Objects.equals(before.getEndDate(),after.getEndDate())
      && Objects.equals(before.getDependsOn(),after.getDependsOn())
      && (Objects.equals(before.getProgress(),100)==Objects.equals(after.getProgress(),100)))return;
    Set<Long> users=new LinkedHashSet<>();
    if(before!=null && before.getAssigneeUserId()!=null)users.add(before.getAssigneeUserId());
    if(after.getAssigneeUserId()!=null)users.add(after.getAssigneeUserId());
    if(after.getAssigneeUserId()==null && after.getDeptId()!=null) {
      for(Long uid:db.queryForList("SELECT id FROM sys_user WHERE org_id=? AND dept_id=? AND status=1 AND deleted=0",Long.class,NotificationPipeline.org(),after.getDeptId()))
        if(auth.business(uid).contains("task:manage"))users.add(uid);
    }
    String title=deleted?"任务已撤销":Objects.equals(after.getProgress(),100)?"任务已完成":before==null?"新任务指派":"任务安排已变更";
    String content="任务「"+after.getName()+"」"+(deleted?"已撤销":"，负责人："+Objects.toString(after.getAssignee(),"待分配")+"，日期："+Objects.toString(after.getStartDate(),"未定")+" 至 "+Objects.toString(after.getEndDate(),"未定"));
    var type=before==null?NotificationType.TASK_ASSIGNED:Objects.equals(after.getProgress(),100)?NotificationType.TASK_COMPLETED:NotificationType.TASK_CHANGED;
    for(Long uid:users)notifications.createAndSave(type,title,content,"TASK",after.getId(),null,uid,null,null);
  }
  public ScheduleSnapshot snapshot(Schedule s) {
    Set<Long> users=new LinkedHashSet<>();
    for(var p:people.findByScheduleId(s.getId()))if(p.getUserId()!=null)users.add(p.getUserId());
    return new ScheduleSnapshot(s.getId(),s.getActivityId(),s.getStatus(),
      Objects.toString(s.getScheduleDate(),"")+" "+Objects.toString(s.getStartTime(),"")+"—"+Objects.toString(s.getEndTime(),"")+" "+Objects.toString(s.getLocation(),""),users);
  }
  @Transactional
  public void scheduleChanged(ScheduleSnapshot before,Schedule after) {
    boolean was=before!=null && Schedule.STATUS_PUBLISHED.equals(before.status());
    boolean now=after!=null && Schedule.STATUS_PUBLISHED.equals(after.getStatus());
    if(!was && !now)return;
    var next=after==null?null:snapshot(after);
    if(was && now && before.details().equals(next.details()) && before.users().equals(next.users()))return;
    NotificationType type=!now?NotificationType.SCHEDULE_CANCELLED:was?NotificationType.SCHEDULE_CHANGED:NotificationType.SCHEDULE_PUBLISHED;
    Set<Long> users=new LinkedHashSet<>();if(was)users.addAll(before.users());if(now)users.addAll(next.users());
    String title=switch(type){case SCHEDULE_CANCELLED->"活动排班已取消";case SCHEDULE_CHANGED->"活动排班已调整";default->"活动排班已发布";};
    String content=(was?"原安排："+before.details()+"；":"")+(now?"当前安排："+next.details():"原安排已失效，请核对最新排班");
    for(Long uid:users)notifications.createAndSave(type,title,content,"SCHEDULE",after==null?before.id():after.getId(),null,uid,null,null);
  }
  @Transactional
  public void planStateChanged(ActivityPlan plan,boolean withdrawn) {
    for(var reviewer:plan.getReviewers()) {
      pipeline.invalidate(reviewer.getUserId());
      if(withdrawn && reviewer.getApproved()==null)notifications.createAndSave(NotificationType.PLAN_WITHDRAWN,
        "策划书审核已撤回","原提交版本已撤回，本轮无需继续审核","PLAN",plan.getId(),plan.getOwnerId(),reviewer.getUserId(),null,null);
    }
  }
}
