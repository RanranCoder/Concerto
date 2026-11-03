package com.pams.module.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.tool.ToolArgs;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.service.NotificationPipeline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AgentNotificationActionService {
    private static final String KIND="sendNotification";
    private final AgentPreparedOperations ops;private final JdbcTemplate db;private final NotificationPipeline pipeline;private final HtmlSanitizer html;
    public AgentNotificationActionService(AgentPreparedOperations ops,JdbcTemplate db,NotificationPipeline pipeline,HtmlSanitizer html){this.ops=ops;this.db=db;this.pipeline=pipeline;this.html=html;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=600000,initialDelay=600000)
    @Transactional public void purgeExpiredAudiences(){db.update("DELETE FROM agent_notification_audience WHERE expires_at<?",LocalDateTime.now());}
    private List<Long> ids(Object value){
        if(value==null)return List.of();if(!(value instanceof List<?> list) || list.size()>100)throw new BizException(400,"选择范围无效或过大");
        var result=new TreeSet<Long>();for(Object v:list){if(v==null || !v.toString().matches("[0-9]{1,18}") || Long.parseLong(v.toString())<=0)throw new BizException(400,"必须使用真实正整数ID");result.add(Long.parseLong(v.toString()));}return List.copyOf(result);
    }
    private Map<String,Object> selector(Map<String,Object> args){
        var roles=new TreeSet<String>();Object raw=args.get("roleCodes");
        if(raw!=null){if(!(raw instanceof List<?> list) || list.size()>20)throw new BizException(400,"角色条件无效");for(Object role:list){if(role==null || !role.toString().matches("[A-Za-z0-9_:-]{1,30}"))throw new BizException(400,"角色代码无效");roles.add(role.toString());}}
        var users=ids(args.get("userIds"));var depts=ids(args.get("deptIds"));String mode=ToolArgs.str(args,"combination");
        if(!Set.of("UNION","INTERSECTION").contains(Objects.toString(mode,"")) || users.isEmpty()&&depts.isEmpty()&&roles.isEmpty())throw new BizException(400,"须明确非空受众条件及UNION/INTERSECTION，不默认全部");
        return Map.of("userIds",users,"deptIds",depts,"roleCodes",List.copyOf(roles),"combination",mode);
    }
    private List<Long> select(ToolContext ctx,Map<String,Object> selector,boolean lock){
        var parameters=new ArrayList<Object>();parameters.add(ctx.getLoginUser().getOrgId());var groups=new ArrayList<String>();
        for(String key:List.of("userIds","deptIds","roleCodes")){
            var values=(List<?>)selector.get(key);if(values.isEmpty())continue;
            String column=switch(key){case "userIds"->"u.id";case "deptIds"->"d.id";default->"r.code";};
            groups.add(column+" IN ("+String.join(",",Collections.nCopies(values.size(),"?"))+")");parameters.addAll(values);
        }
        String sql="SELECT u.id FROM sys_user u JOIN sys_role r ON r.id=u.role_id AND r.org_id=u.org_id LEFT JOIN sys_department d ON d.id=u.dept_id AND d.org_id=u.org_id WHERE u.org_id=? AND u.deleted=0 AND u.status=1 AND r.status='ACTIVE' AND ("
                +String.join("INTERSECTION".equals(selector.get("combination"))?" AND ":" OR ",groups)+") ORDER BY u.id LIMIT 201"+(lock?" FOR UPDATE":"");
        var users=db.queryForList(sql,Long.class,parameters.toArray());if(users.size()>200)throw new BizException(400,"受众超过200人，请缩小范围或使用原生发布界面");return users;
    }
    @Transactional
    public ToolResult resolve(ToolContext ctx,Map<String,Object> args){
        ops.guard(ctx,"notification:send",false);var selector=selector(args);var recipients=select(ctx,selector,false);
        if(recipients.isEmpty())throw new BizException(404,"未匹配到当前组织可接收通知的账号；成员不一定有可接收账号");
        String id=UUID.randomUUID().toString();var expires=LocalDateTime.now().plusMinutes(15);
        db.update("INSERT INTO agent_notification_audience(id,org_id,user_id,selector_json,recipients_json,expires_at) VALUES(?,?,?,?,?,?)",id,ctx.getLoginUser().getOrgId(),ctx.getUserId(),ops.encode(selector),ops.encode(recipients),expires);
        return ToolResult.ok("已解析授权受众快照，仅提供数量与句柄；不披露账号名单。",ToolArgs.map("audienceRef",id,"count",recipients.size(),"combination",selector.get("combination"),"expiresAt",expires.toString(),"exclusionSummary","仅包含当前组织启用账号及有效角色；未匹配或不可接收对象不进入快照"));
    }
    private Map<String,Object> audience(ToolContext ctx,String id,boolean lock){
        if(id==null)throw new BizException(400,"需要受众快照");
        var rows=db.queryForList("SELECT * FROM agent_notification_audience WHERE id=? AND org_id=? AND user_id=?"+(lock?" FOR UPDATE":""),id,ctx.getLoginUser().getOrgId(),ctx.getUserId());
        if(rows.isEmpty())throw new BizException(404,"受众快照不存在或不可见");var row=rows.getFirst();
        if(AgentPreparedOperations.dateTime(row.get("expires_at")).isBefore(LocalDateTime.now()))throw new BizException(409,"受众快照已过期，请重新解析和预览");return row;
    }
    @Transactional
    public ToolResult prepare(ToolContext ctx,Map<String,Object> args){
        ops.guard(ctx,"notification:send",true);String title=ToolArgs.str(args,"title"),content=ToolArgs.str(args,"content");
        if(title==null || title.isBlank() || title.length()>100 || content==null || content.isBlank())throw new BizException(400,"标题/正文无效；标题最多100字");
        content=html.toPlainText(html.sanitize(content));if(content.isBlank() || content.length()>500)throw new BizException(400,"通知正文须为1–500字；不会静默截断");
        if(args.get("entityType")!=null || args.get("entityId")!=null)throw new BizException(400,"首版定向通知不绑定业务实体，避免未经校验的链接与范围");
        String ref=ToolArgs.str(args,"audienceRef");
        boolean legacy=args.get("recipientUserId")!=null || args.get("recipientRole")!=null || args.get("recipientDeptId")!=null;
        if(ref!=null && legacy)throw new BizException(400,"受众快照与直接选择条件不能混用");
        if(ref==null){
            int count=(args.get("recipientUserId")==null?0:1)+(args.get("recipientRole")==null?0:1)+(args.get("recipientDeptId")==null?0:1);
            String mode=ToolArgs.str(args,"combination");if(count==1 && mode==null)mode="UNION";
            var input=ToolArgs.map("userIds",args.get("recipientUserId")==null?List.of():List.of(args.get("recipientUserId")),"deptIds",args.get("recipientDeptId")==null?List.of():List.of(args.get("recipientDeptId")),"roleCodes",args.get("recipientRole")==null?List.of():List.of(args.get("recipientRole")),"combination",mode);
            ref=resolve(ctx,input).payload().get("audienceRef").toString();
        }
        var snapshot=audience(ctx,ref,false);var selector=ops.decode(snapshot.get("selector_json").toString());
        var recipients=ops.json.convertValue(ops.decode("{\"ids\":"+snapshot.get("recipients_json")+"}").get("ids"),new TypeReference<List<Long>>(){});
        if(!recipients.equals(select(ctx,selector,false)))throw new BizException(409,"STALE_AUDIENCE：受众已变化，请重新解析和预览");
        String id=ops.create(ctx,KIND,Map.of("title",title,"content",content,"audienceRef",ref,"audienceCount",recipients.size()));
        return ToolResult.plan("已准备通知预览，尚未提交投递。",ops.plan(id,"定向通知："+title,List.of(new AgentActionPlan.PlanItem(null,"接收人数",recipients.size()+"人")),"# "+title+"\n\n"+content+"\n\n接收人数："+recipients.size()+"人",
                "受众快照："+ref,"确认时再次核对受众集合；变化或过期须重新预览。","确认后提交Outbox；入箱、信号投递和阅读是不同状态。"));
    }
    @Transactional(rollbackFor=Exception.class)
    public WriteAgentTool.CommitResult commit(ToolContext ctx,AgentActionPlan action){
        ops.guard(ctx,"notification:send",true);var op=ops.require(ctx,action,KIND,true);if(op.result()!=null)return op.result();
        var snapshot=audience(ctx,op.payload().get("audienceRef").toString(),true);var selector=ops.decode(snapshot.get("selector_json").toString());
        var recipients=ops.json.convertValue(ops.decode("{\"ids\":"+snapshot.get("recipients_json")+"}").get("ids"),new TypeReference<List<Long>>(){});
        if(!recipients.equals(select(ctx,selector,true)))throw new BizException(409,"STALE_AUDIENCE：受众集合已变化，请重新预览，不会自动扩大或缩减范围");
        var saved=pipeline.recordFrozen(NotificationType.COLLABORATION,op.payload().get("title").toString(),op.payload().get("content").toString(),ctx.getUserId(),recipients,op.id());
        var result=new WriteAgentTool.CommitResult("SUCCESS",1,0,"已提交通知投递；尚不能据此声称已入箱、在线送达或已阅读。",ToolArgs.map("notificationId",saved.getId(),"audienceCount",(long)recipients.size(),"operationId",op.id()),List.of(ToolArgs.map("type","notification","id",saved.getId(),"label",saved.getTitle(),"link","/notifications")));
        ops.finish(op.id(),result);return result;
    }
    @Transactional(readOnly=true)
    public ToolResult status(ToolContext ctx,Long id){
        ops.guard(ctx,"notification:send",false);if(id==null)throw new BizException(400,"需要notificationId");
        var rows=db.queryForList("SELECT id FROM notification WHERE id=? AND org_id=? AND sender_id=? AND deleted=0",id,ctx.getLoginUser().getOrgId(),ctx.getUserId());
        if(rows.isEmpty())throw new BizException(404,"通知不存在或不可见");long org=ctx.getLoginUser().getOrgId();
        var outbox=db.queryForList("SELECT status,recipient_count,skipped_count FROM notification_outbox WHERE org_id=? AND message_id=?",org,id);
        long expected=db.queryForObject("SELECT COUNT(*) FROM notification_outbox_recipient r JOIN notification_outbox o ON o.id=r.outbox_id WHERE o.org_id=? AND o.message_id=?",Long.class,org,id);
        long inbox=db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE org_id=? AND message_id=?",Long.class,org,id);
        long read=db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE org_id=? AND message_id=? AND read_at IS NOT NULL",Long.class,org,id);
        var signals=db.queryForList("SELECT status,COUNT(*) AS count FROM notification_delivery WHERE org_id=? AND message_id=? GROUP BY status ORDER BY status",org,id);
        boolean materialized=outbox.size()==1 && "COMPLETED".equals(outbox.getFirst().get("status"));
        return ToolResult.ok("通知提交、入箱、在线信号和阅读分别统计；信号入队不等于用户已看到。",ToolArgs.map("notificationId",id,"submission","ACCEPTED","outbox",outbox,"frozenAudienceCount",expected,"inboxCount",inbox,"readCount",read,
                "materializationComplete",materialized,"allInboxDelivered",expected>0 && inbox==expected,"signalStates",signals,"asOf",java.time.Instant.now().toString()));
    }
    @Transactional(readOnly=true)
    public Optional<WriteAgentTool.CommitResult> confirmedReceipt(ToolContext ctx,AgentActionPlan action){ops.guard(ctx,"notification:send",false);var op=ops.require(ctx,action,KIND,false);if(op.result()!=null)status(ctx,((Number)op.result().detail().get("notificationId")).longValue());return Optional.ofNullable(op.result());}
}
