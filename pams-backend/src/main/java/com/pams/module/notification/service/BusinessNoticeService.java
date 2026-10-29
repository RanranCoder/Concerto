package com.pams.module.notification.service;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.permission.service.TenantAuthorizationService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** Aggregate counts inside the business transaction; never accepts names, scores or free-text details. */
@Service
public class BusinessNoticeService {
  public enum Domain {
    QUALITY("素拓记录","quality:view","quality:add",false),
    QUALITY_DELETE("素拓记录","quality:view","quality:delete",false),
    PARTY_STAGE("党务阶段","party:view","party:manage",false),
    PARTY_RECORD("党务台账","party:view","party:entry",false),
    PARTY_LETTER("党务函调","party:view","party:letter",false),
    MEMBER_IMPORT("成员导入","member:view","member:view",true),
    PARTY_IMPORT("党务名单导入","party:view","party:entry",true),
    SCHEDULE_IMPORT("无课表导入","schedule:free_table","schedule:free_table",true);
    public final String label,view,operate;public final boolean personal;
    Domain(String label,String view,String operate,boolean personal){this.label=label;this.view=view;this.operate=operate;this.personal=personal;}
  }
  public enum Action { ADD,UPDATE,DELETE,STAGE,IMPORTED,SKIPPED,FAILED,ROLLBACK }
  private record Key(long org,Domain domain,Long actor) {}
  private static final Object RESOURCE=new Object();
  private final JdbcTemplate db;private final TenantAuthorizationService auth;private final NotificationPipeline pipeline;
  public BusinessNoticeService(JdbcTemplate db,TenantAuthorizationService auth,NotificationPipeline pipeline){this.db=db;this.auth=auth;this.pipeline=pipeline;}
  @Transactional(propagation=Propagation.MANDATORY)
  public void add(Domain domain,Action action,int count,Long actor) {
    if(count<=0)return;
    long org=NotificationPipeline.org();
    Long current=ToolContext.current(null,null).getUserId();
    if(current!=null)actor=current;
    @SuppressWarnings("unchecked")
    var batches=(Map<Key,EnumMap<Action,Integer>>)TransactionSynchronizationManager.getResource(RESOURCE);
    if(batches==null) {
      batches=new LinkedHashMap<>();var captured=batches;
      TransactionSynchronizationManager.bindResource(RESOURCE,batches);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void suspend(){TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);}
        @Override public void resume(){TransactionSynchronizationManager.bindResource(RESOURCE,captured);}
        @Override public void beforeCommit(boolean readOnly){captured.forEach(BusinessNoticeService.this::emit);}
        @Override public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);}
      });
    }
    batches.computeIfAbsent(new Key(org,domain,actor),k->new EnumMap<>(Action.class)).merge(action,count,Integer::sum);
  }
  private void emit(Key key,EnumMap<Action,Integer> counts) {
    if(NotificationPipeline.org()!=key.org())throw new IllegalStateException("业务摘要组织不一致");
    Domain domain=key.domain();
    var labels=Map.of(Action.ADD,"新增",Action.UPDATE,"调整",Action.DELETE,"删除",Action.STAGE,"阶段变更",Action.IMPORTED,"导入成功",Action.SKIPPED,"跳过",Action.FAILED,"未导入",Action.ROLLBACK,"回滚");
    String content=String.join("，",counts.entrySet().stream().map(e->labels.get(e.getKey())+" "+e.getValue()+" 项").toList())+"。详情请回业务页面核对。";
    List<Long> recipients=domain.personal?(key.actor()==null?List.of():List.of(key.actor())):
      db.queryForList("SELECT id FROM sys_user WHERE org_id=? AND status=1 AND deleted=0",Long.class,key.org());
    for(long uid:recipients) {
      if(!auth.valid(uid) || ((Number)auth.identity(uid).get("org_id")).longValue()!=key.org())continue;
      var permissions=auth.business(uid);
      if(!permissions.contains(domain.view) || !permissions.contains(domain.operate))continue;
      pipeline.record(domain.personal?NotificationType.IMPORT_RESULT:domain.name().startsWith("QUALITY")?NotificationType.QUALITY_SUMMARY:NotificationType.PARTY_SUMMARY,
          domain.label+"处理摘要",content,domain.name(),key.org(),key.actor(),uid,null,null);
    }
  }
}
