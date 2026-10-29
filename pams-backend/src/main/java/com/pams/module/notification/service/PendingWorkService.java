package com.pams.module.notification.service;

import com.pams.common.BizException;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.permission.service.TenantAuthorizationService;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One current-state query contract for dashboard, inbox and agent. Never derives work from unread messages. */
@Service
@Transactional(readOnly=true)
public class PendingWorkService {
  public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
  public record Item(String key,String type,String entityType,long entityId,Long activityId,String title,
                     String action,LocalDateTime dueAt,boolean overdue,Integer progress,String assignee) {}
  public record Summary(int total,long overdue,Map<String,Long> counts,List<Item> items) {}
  private final JdbcTemplate db;
  private final TenantAuthorizationService auth;
  public PendingWorkService(JdbcTemplate db,TenantAuthorizationService auth){this.db=db;this.auth=auth;}
  public Summary summary(ToolContext ctx){return summary(ctx,LocalDateTime.now(ZONE));}
  public Summary summary(ToolContext ctx,LocalDateTime now) {
    if(ctx==null || ctx.getUserId()==null)return new Summary(0,0,Map.of(),List.of());
    long org=NotificationPipeline.org(),uid=ctx.getUserId();
    if(!auth.valid(uid) || ((Number)auth.identity(uid).get("org_id")).longValue()!=org
        || !Objects.equals(ctx.getLoginUser().getOrgId(),org))throw new BizException(403,"当前身份无权查询该组织待办");
    var live=auth.business(uid);
    java.util.function.Predicate<String> can=p->ctx.hasPermission(p) && live.contains(p);
    List<Item> items=new ArrayList<>();
    if(can.test("task:view")) items.addAll(db.query("SELECT t.* FROM task t JOIN activity a ON a.id=t.activity_id AND a.org_id=t.org_id "
      +"WHERE t.org_id=? AND t.assignee_user_id=? AND t.deleted=0 AND a.deleted=0 AND a.status<>'ARCHIVED' "
      +"AND t.status<>'DONE' AND COALESCE(t.progress,0)<100",(r,n)->{
        LocalDate date=r.getObject("end_date",LocalDate.class);
        return new Item("TASK:"+r.getLong("id"),"TASK","TASK",r.getLong("id"),r.getLong("activity_id"),r.getString("name"),"处理任务",
          date==null?null:date.atTime(23,59,59),date!=null && date.isBefore(now.toLocalDate()),r.getObject("progress",Integer.class),r.getString("assignee"));
      },org,uid));
    if(can.test("activity:view") && can.test("plan:view") && can.test("plan:review"))items.addAll(db.query(
      "SELECT p.id,p.activity_id,COALESCE(p.name_override,a.name) AS title FROM activity_plan p JOIN activity_plan_review r ON r.plan_id=p.id "
      +"JOIN activity a ON a.id=p.activity_id AND a.org_id=p.org_id WHERE p.org_id=? AND a.deleted=0 AND a.status<>'ARCHIVED' "
      +"AND p.status='PENDING' AND r.reviewer_id=? AND r.approved IS NULL "
      +"AND NOT EXISTS(SELECT 1 FROM activity_plan newer WHERE newer.org_id=p.org_id AND newer.activity_id=p.activity_id AND newer.version>p.version)",
      (r,n)->new Item("PLAN:"+r.getLong("id"),"PLAN_REVIEW","PLAN",r.getLong("id"),r.getLong("activity_id"),r.getString("title"),"审核策划书",null,false,null,null),org,uid));
    if(can.test("content:view")) {
      if(can.test("article:edit") || can.test("article:submit"))articleItems(items,org,uid,"a.author_id=? AND a.status IN ('DRAFT','REJECTED')","ARTICLE_WRITE",can.test("article:edit")?"撰写推文":"提交推文审核",now);
      if(can.test("article:review"))articleItems(items,org,uid,"a.author_id<>? AND a.status='PENDING'","ARTICLE_REVIEW","审核推文",now);
      if(can.test("article:publish"))articleItems(items,org,uid,can.test("article:manage")?"? IS NOT NULL AND a.status='APPROVED'":"a.author_id=? AND a.status='APPROVED'","ARTICLE_PUBLISH","发布推文",now);
    }
    if(can.test("schedule:self:view") || can.test("schedule:view"))items.addAll(db.query(
      "SELECT DISTINCT s.* FROM schedule s JOIN schedule_person p ON p.schedule_id=s.id AND p.org_id=s.org_id "
      +"LEFT JOIN attendance a ON a.schedule_id=s.id AND a.person_id=p.user_id AND a.org_id=s.org_id "
      +"WHERE s.org_id=? AND p.user_id=? AND s.status='PUBLISHED' AND s.schedule_date IS NOT NULL "
      +"AND (s.activity_id IS NULL OR EXISTS(SELECT 1 FROM activity act WHERE act.id=s.activity_id AND act.org_id=s.org_id AND act.deleted=0 AND act.status<>'ARCHIVED')) "
      +"AND (a.id IS NULL OR a.status='PENDING')",(r,n)->{
        LocalDate date=r.getObject("schedule_date",LocalDate.class);
        LocalTime start=r.getObject("start_time",LocalTime.class),end=r.getObject("end_time",LocalTime.class);
        if(end==null ? date.isBefore(now.toLocalDate()) : date.atTime(end).isBefore(now))return null;
        return new Item("SCHEDULE:"+r.getLong("id"),"SCHEDULE","SCHEDULE",r.getLong("id"),r.getObject("activity_id",Long.class),
          "值班安排 · "+date+" "+Objects.toString(r.getString("location"),"地点待定"),"查看排班",start==null?null:date.atTime(start),false,null,null);
      },org,uid).stream().filter(Objects::nonNull).toList());
    if(can.test("notice:view"))items.addAll(db.query("SELECT a.id,a.title FROM announcement a JOIN announcement_receipt r ON r.announcement_id=a.id AND r.org_id=a.org_id AND r.version=a.confirmation_version WHERE a.org_id=? AND a.deleted=0 AND a.require_confirmation=TRUE AND r.user_id=? AND r.confirmed_at IS NULL",
      (r,n)->new Item("ANNOUNCEMENT:"+r.getLong("id"),"ANNOUNCEMENT_CONFIRM","ANNOUNCEMENT",r.getLong("id"),null,r.getString("title"),"确认公告",null,false,null,null),org,uid));
    items.sort(Comparator.comparing(Item::overdue).reversed().thenComparing(Item::dueAt,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Item::key));
    Map<String,Long> counts=new LinkedHashMap<>();items.forEach(i->counts.merge(i.type(),1L,Long::sum));
    return new Summary(items.size(),items.stream().filter(Item::overdue).count(),counts,List.copyOf(items));
  }
  private void articleItems(List<Item> items,long org,long uid,String predicate,String type,String action,LocalDateTime now) {
    items.addAll(db.query("SELECT a.* FROM article a LEFT JOIN activity act ON act.id=a.activity_id AND act.org_id=a.org_id "
      +"WHERE a.org_id=? AND a.deleted=0 AND (act.id IS NULL OR (act.deleted=0 AND act.status<>'ARCHIVED')) AND "+predicate,(r,n)->{
        LocalDateTime due=r.getObject("deadline",LocalDateTime.class);
        return new Item(type+":"+r.getLong("id"),type,"ARTICLE",r.getLong("id"),r.getObject("activity_id",Long.class),r.getString("title"),action,due,due!=null && due.isBefore(now),null,null);
      },org,uid));
  }
}
