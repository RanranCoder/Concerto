package com.pams.module.agent.service;
import com.pams.PartyAffairsManagementSystemApplication;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.orchestration.*;
import com.pams.module.agent.repository.*;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.LoginUser;
import org.springframework.boot.SpringApplication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;

/** A real child JVM with only disposable-schema credentials. No model calls. */
public class BatchSixRuntimeProcess {
    public static void main(String[] args)throws Exception{
        String url=System.getenv("SPRING_DATASOURCE_URL");if(!"true".equals(System.getenv("PAMS_S5_BATCH6"))||url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pams_s5_batch6_[a-f0-9]{12}\\?.*"))throw new IllegalStateException("Refusing non-batch6 sandbox");
        var app=SpringApplication.run(PartyAffairsManagementSystemApplication.class,"--spring.profiles.active=test","--server.port="+("browser".equals(args[0])?"18080":"0"),"--pams.upload-dir="+Path.of(args[1]).toAbsolutePath().getParent().resolve("browser-files"),"--pams.agent.provider=stub","--pams.agent.controlled-planning-enabled=true","--pams.agent.controlled-execution-enabled=true","--pams.agent.controlled-planning-org-ids=1","--logging.level.root=WARN","--spring.main.banner-mode=off");
        if("browser".equals(args[0])){BatchSixBrowserFixture.seed(app,Path.of(args[1]));return;}
        try{
            OrgContext.set(1L);var auth=app.getBean(TenantAuthorizationService.class);var user=new LoginUser(1L,"teacher","B6重启验收","TEACHER",5,null,null,1L);var ctx=new ToolContext(user,auth.authorities(1L).stream().map(SimpleGrantedAuthority::new).toList(),null,null).withTimezone("Asia/Shanghai");
            var articles=app.getBean(ArticleRepository.class);var editorial=app.getBean(AgentEditorialService.class);var gate=app.getBean(TaskGate.class);var tasks=app.getBean(AgentTaskRepository.class);var tools=app.getBean(ToolRegistry.class);var mapper=AgentPreparedOperations.mapper();var checkpoint=Path.of(args[1]);
            if("prepare".equals(args[0])){
                var cases=new ArrayList<Map<String,Object>>();
                for(String kind:List.of("PENDING","COMMITTED")){
                    var a=new Article();a.setTitle("B6-JVM-"+UUID.randomUUID());a.setContent("预算2500元");a.setAuthorId(1L);a.setStatus(Article.ArticleStatus.DRAFT);a.setArticleType(Article.ArticleType.REPORT);a.setDeleted(0);a=articles.saveAndFlush(a);
                    var read=editorial.readArticle(ctx,Map.of("articleId",a.getId()),false);var raw=editorial.prepare("prepareArticleRevision",ctx,Map.of("articleId",a.getId(),"baseline",read.payload().get("baseline"),"changes",Map.of("content",Map.of("before","2500元","after","3000元")))).plan();var t=tools.find("prepareArticleRevision").orElseThrow();
                    var p=gate.createPlan(ctx,t,raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,raw.preview());
                    if(!"PENDING".equals(kind)){
                        if(tasks.claimOwned(p.planId(),"PENDING_CONFIRM","RUNNING",java.time.LocalDateTime.now(),RuntimeIdentity.ID)!=1)throw new IllegalStateException("claim failed");
                        editorial.commit(t.name(),ctx,p);
                    }
                    cases.add(Map.of("kind",kind,"taskId",p.planId(),"articleId",a.getId()));
                }
                var target=articles.findById(((Number)cases.getFirst().get("articleId")).longValue()).orElseThrow();var run=app.getBean(AgentRunService.class).startRun(null,1L,"读取稿件"+target.getId(),"P1");var bb=new Blackboard();var n=new Blackboard.PlanItem();n.setId(1);n.setAgent("writing");n.setTask("读取明确稿件原文");var tool=tools.find("readArticleDocument").orElseThrow();
                n.setContract(Map.of("version","controlled-node/v1","goal",run.getGoal(),"capability",tool.name(),"write",false,"schema",tool.parametersSchema(),"completionPolicy","TOOL_RESULT_OK","completionCriteria","工具返回成功原文","inputBindings",List.of(Map.of("parameter","articleId","source","USER","quote",target.getId().toString()))));bb.getPlan().add(n);app.getBean(WorkItemState.class).initialize(run.getId(),bb,ctx.withRunId(run.getId()));cases.add(Map.of("kind","CONTROLLED_QUEUED","runId",run.getId()));
                var pending=new Article();pending.setTitle("B6-inflight-"+UUID.randomUUID());pending.setContent("预算2500元");pending.setAuthorId(1L);pending.setStatus(Article.ArticleStatus.DRAFT);pending.setArticleType(Article.ArticleType.REPORT);pending.setDeleted(0);pending=articles.saveAndFlush(pending);
                var read=editorial.readArticle(ctx,Map.of("articleId",pending.getId()),false);var raw=editorial.prepare("prepareArticleRevision",ctx,Map.of("articleId",pending.getId(),"baseline",read.payload().get("baseline"),"changes",Map.of("content",Map.of("before","2500元","after","3000元")))).plan();var write=tools.find("prepareArticleRevision").orElseThrow();
                var inFlight=gate.createPlan(ctx,write,raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,raw.preview());
                if(tasks.claimOwned(inFlight.planId(),"PENDING_CONFIRM","RUNNING",java.time.LocalDateTime.now(),RuntimeIdentity.ID)!=1)throw new IllegalStateException("in-flight claim failed");
                cases.add(Map.of("kind","IN_FLIGHT","taskId",inFlight.planId(),"articleId",pending.getId(),"checkpointPhase","BUSINESS_SQL_FLUSHED_OUTER_TRANSACTION_OPEN"));
                new TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(status->{
                    editorial.commit(write.name(),ctx,inFlight);
                    try{Path temp=checkpoint.resolveSibling(checkpoint.getFileName()+".tmp");Files.writeString(temp,mapper.writeValueAsString(cases));Files.move(temp,checkpoint,StandardCopyOption.ATOMIC_MOVE);new java.util.concurrent.CountDownLatch(1).await();}
                    catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;
                });
            }else if("verify".equals(args[0])){
                var evidence=new ArrayList<Map<String,Object>>();for(var row:mapper.readTree(Files.readString(checkpoint))){String kind=row.path("kind").asText();
                    if("CONTROLLED_QUEUED".equals(kind)){long id=row.path("runId").asLong();var run=app.getBean(WorkItemState.class).owned(id,1L);if(!"Asia/Shanghai".equals(run.getUserTimezone())||run.getPromptBundleJson()==null)throw new IllegalStateException("pinned runtime lost");com.pams.module.agent.prompt.PromptBundle.text(run.getPromptBundleJson(),com.pams.module.agent.prompt.PromptResolver.Id.EXECUTOR);var bb=app.getBean(DagScheduler.class).drive(id,1L);if(!"SUCCESS".equals(bb.executionState()))throw new IllegalStateException("controlled recovery failed");var node=app.getBean(WorkItemState.class).nodes(id).getFirst();if(node.getContractJson()==null||node.getResultJson()==null)throw new IllegalStateException("contract/receipt missing");evidence.add(Map.of("kind",kind,"runId",id,"status","SUCCESS","promptBundleRecovered",true,"timezoneRecovered","Asia/Shanghai"));continue;}
                    long taskId=row.path("taskId").asLong(),articleId=row.path("articleId").asLong();var task=tasks.findById(taskId).orElseThrow();
                    if("PENDING".equals(kind)){gate.confirm(taskId,task.getConfirmToken(),ctx);gate.confirm(taskId,task.getConfirmToken(),ctx);if(!"SUCCESS".equals(tasks.findById(taskId).orElseThrow().getStatus())||articles.findById(articleId).orElseThrow().getRevision()!=1)throw new IllegalStateException("pending confirmation replayed or lost");}
                    else {var result=app.getBean(ActionReconciliationService.class).reconcile(taskId,ctx);boolean verified=Boolean.TRUE.equals(result.get("reconciled"));if(verified!="COMMITTED".equals(kind))throw new IllegalStateException("incorrect receipt reconciliation");gate.confirm(taskId,task.getConfirmToken(),ctx);long expected="COMMITTED".equals(kind)?1:0;if(articles.findById(articleId).orElseThrow().getRevision()!=expected)throw new IllegalStateException("uncertain write was replayed");if("IN_FLIGHT".equals(kind)&&!"RESULT_UNKNOWN".equals(tasks.findById(taskId).orElseThrow().getStatus()))throw new IllegalStateException("interrupted action not explicitly blocked");}
                    evidence.add(Map.of("kind",kind,"taskId",taskId,"articleId",articleId,"revision",articles.findById(articleId).orElseThrow().getRevision(),"status",tasks.findById(taskId).orElseThrow().getStatus()));
                }
                mapper.writerWithDefaultPrettyPrinter().writeValue(checkpoint.resolveSibling("process-recovery-evidence.json").toFile(),Map.of("actualJvmKilled",true,"status","PASS","cases",evidence));
            }else throw new IllegalArgumentException("phase");
        }finally{OrgContext.clear();app.close();}
    }
}
