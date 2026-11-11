package com.pams.module.agent.service;

import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.LoginUser;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.nio.file.*;
import java.util.*;

/** Test-classpath only. Persist real previews; browser uses unmodified authenticated HTTP APIs. */
final class BatchSixBrowserFixture {
    static void seed(ConfigurableApplicationContext app,Path ready)throws Exception{
        OrgContext.set(1L);
        try{
            var auth=app.getBean(TenantAuthorizationService.class);var user=new LoginUser(1L,"teacher","浏览器验收","TEACHER",5,null,null,1L);
            var base=new ToolContext(user,auth.authorities(1L).stream().map(SimpleGrantedAuthority::new).toList(),null,null);
            var articles=app.getBean(ArticleRepository.class);var editorial=app.getBean(AgentEditorialService.class);
            var activity=new Activity();activity.setName("B6浏览器活动");activity.setType("OTHER");activity.setStatus(ActivityStatus.EXECUTING);activity.setDeleted(0);activity.setCreatedBy(1L);activity=app.getBean(ActivityRepository.class).saveAndFlush(activity);
            var rows=new ArrayList<Map<String,Object>>();
            if ("true".equals(System.getenv("PAMS_AGENT_RELEASE_BROWSER_REAL"))) {
                var db = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                var provider = com.pams.module.agent.orchestration.ControlledPlanningRealModelTest.installTestProvider(
                        db, app.getEnvironment(), app.getBean(com.pams.module.agent.provider.ProviderSecrets.class), true);
                rows.add(Map.of("kind", "LIVE_WORD", "activityId", activity.getId(), "model", provider.get("model")));
                AgentPreparedOperations.mapper().writerWithDefaultPrettyPrinter().writeValue(ready.toFile(),
                        Map.of("pid", ProcessHandle.current().pid(), "database", db.queryForObject("select database()", String.class), "cases", rows));
                return;
            }
            for(String kind:List.of("REVISION","ARTICLE_SUBMIT","STALE")){
                var a=new Article();a.setTitle("B6-"+kind);a.setContent("<p>预算2500元，日期保留。</p>");a.setSummary("摘要保留");a.setAuthorId(1L);a.setActivityId(activity.getId());a.setArticleType(Article.ArticleType.REPORT);a.setStatus(Article.ArticleStatus.DRAFT);a.setDeleted(0);a=articles.saveAndFlush(a);
                var ctx=conversation(app,base,kind);String tool=kind.equals("ARTICLE_SUBMIT")?"prepareArticleSubmission":"prepareArticleRevision";
                var arguments=new LinkedHashMap<String,Object>();arguments.put("articleId",a.getId());arguments.put("baseline",editorial.readArticle(ctx,Map.of("articleId",a.getId()),false).payload().get("baseline"));
                if(!kind.equals("ARTICLE_SUBMIT"))arguments.put("changes",Map.of("content",Map.of("before","2500元","after","3000元")));
                var p=persist(app,ctx,tool,editorial.prepare(tool,ctx,arguments));
                if(kind.equals("STALE")){a=articles.findById(a.getId()).orElseThrow();a.setContent("原生并发修改保留");articles.saveAndFlush(a);}
                rows.add(Map.of("kind",kind,"articleId",a.getId(),"taskId",p.planId(),"conversationId",ctx.getConversationId()));
            }
            var p=new ActivityPlan();p.setActivityId(activity.getId());p.setOwnerId(1L);p.setVersion(1);p.setStatus(ActivityPlan.PlanStatus.DRAFT);p.setContent("原稿必须保留");p=app.getBean(ActivityPlanRepository.class).saveAndFlush(p);
            var pc=conversation(app,base,"PLAN_SUBMIT");var candidates=app.getBean(com.pams.module.activity.service.PlanAccessService.class).candidates(pc);
            var pp=persist(app,pc,"preparePlanSubmission",editorial.prepare("preparePlanSubmission",pc,Map.of("planId",p.getId(),"expectedRevision",p.getRevision(),"reviewerIds",List.of(candidates.getFirst().id()))));
            rows.add(Map.of("kind","PLAN_SUBMIT","planId",p.getId(),"taskId",pp.planId(),"conversationId",pc.getConversationId()));
            var sc=conversation(app,base,"SCHEDULE");var schedules=app.getBean(ScheduleRepository.class);var ids=new ArrayList<Long>();
            for(int i=0;i<2;i++){var s=new Schedule();s.setActivityId(activity.getId());s.setScheduleType("ACTIVITY");s.setStatus("DRAFT");s.setScheduleDate(java.time.LocalDate.of(2026,12,1));s.setStartTime(java.time.LocalTime.of(8,0));s.setEndTime(java.time.LocalTime.of(9,0));s.setSource("MANUAL");ids.add(schedules.saveAndFlush(s).getId());}
            var service=app.getBean(AgentSchedulePublicationService.class);var id=ids.getFirst();
            var sp=persist(app,sc,"prepareSchedulePublication",app.getBean(ToolRegistry.class).find("prepareSchedulePublication").orElseThrow().execute(sc,Map.of("scheduleIds",List.of(id),"baselines",Map.of(id.toString(),service.metadata(sc,id).get("baseline")))));
            rows.add(Map.of("kind","SCHEDULE","scheduleId",id,"excludedScheduleId",ids.get(1),"taskId",sp.planId(),"conversationId",sc.getConversationId()));
            seedReplan(app,base,rows);
            seedDocx(app,base,rows);
            var data=Map.of("pid",ProcessHandle.current().pid(),"database",app.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject("select database()",String.class),"cases",rows);
            AgentPreparedOperations.mapper().writerWithDefaultPrettyPrinter().writeValue(ready.toFile(),data);
        }finally{OrgContext.clear();}
    }
    static void seedDocx(ConfigurableApplicationContext app,ToolContext base,List<Map<String,Object>> rows)throws Exception{
        var ctx=conversation(app,base,"DOCX_REVISION");
        var a=new Activity();a.setName("B6原生预算修订");a.setType("OTHER");a.setStatus(ActivityStatus.ASSIGNED);a.setDeleted(0);a.setCreatedBy(1L);a=app.getBean(ActivityRepository.class).saveAndFlush(a);
        byte[] source;
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("不改此段落");var run=doc.createParagraph().createRun();run.setBold(true);run.setText("预算总额为2500元");doc.createParagraph().createRun().setText("原流程保留");doc.write(out);source=out.toByteArray();
        }
        var file=app.getBean(com.pams.module.archive.service.FileStorageService.class).store(new org.springframework.mock.web.MockMultipartFile("file","预算原稿.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",source),"PLAN",1L);
        var p=new ActivityPlan();p.setActivityId(a.getId());p.setOwnerId(1L);p.setVersion(1);p.setStatus(ActivityPlan.PlanStatus.DRAFT);p.setDocumentFileId(file.getId());p=app.getBean(ActivityPlanRepository.class).saveAndFlush(p);
        var read=app.getBean(AgentPlanReadService.class).document(ctx,p.getId(),null,0);if(!Boolean.TRUE.equals(read.payload().get("revisionEligible")))throw new IllegalStateException("DOCX fixture not eligible");
        var blocks=(List<Map<String,Object>>)read.payload().get("blocks");var target=blocks.stream().filter(b->b.get("text").toString().contains("预算")).findFirst().orElseThrow();
        var tool=app.getBean(ToolRegistry.class).find("preparePlanRevision").orElseThrow();var raw=tool.execute(ctx,Map.of("planId",p.getId(),"documentRef",read.payload().get("documentRef"),"baseline",read.payload().get("baseline"),"blockId",target.get("blockId"),"expectedHash",target.get("hash"),"before",target.get("text"),"after","预算总额为3000元"));
        var pending=persist(app,ctx,tool.name(),raw);
        rows.add(Map.of("kind","DOCX_REVISION","planId",p.getId(),"activityId",a.getId(),"taskId",pending.planId(),"sourceSha256",PlanDocumentReader.hash(source),"conversationId",ctx.getConversationId()));
    }
    static void seedReplan(ConfigurableApplicationContext app,ToolContext base,List<Map<String,Object>> rows)throws Exception{
        var ctx=conversation(app,base,"REPLAN");var repository=app.getBean(ArticleRepository.class);
        var article=new Article();article.setTitle("重规划待送审稿");article.setContent("保留原文");article.setAuthorId(1L);article.setArticleType(Article.ArticleType.REPORT);article.setStatus(Article.ArticleStatus.DRAFT);article.setDeleted(0);article=repository.saveAndFlush(article);
        String goal="读取稿件"+article.getId()+"再提交审核";var bb=new com.pams.module.agent.orchestration.Blackboard();bb.setGoal(goal);bb.setMode("P1");
        var names=List.of("readArticleDocument","prepareArticleSubmission","getArticleReviewStatus");var tools=app.getBean(ToolRegistry.class);
        for(int i=0;i<3;i++){
            var n=new com.pams.module.agent.orchestration.Blackboard.PlanItem();n.setId(i+1);n.setAgent(i==2?"retrieval":"writing");n.setTask("核验指定稿件");n.setDependsOn(i==0?List.of():List.of(i));var tool=tools.find(names.get(i)).orElseThrow();
            var bindings=new ArrayList<Map<String,Object>>();
            if(i==2)bindings.add(Map.of("parameter","articleId","source","STEP","stepId",2,"path","detail.articleId"));
            else bindings.add(Map.of("parameter","articleId","source","USER","quote",article.getId().toString()));
            if(i==1)bindings.add(Map.of("parameter","baseline","source","STEP","stepId",1,"path","payload.baseline"));
            n.setContract(Map.of("version","controlled-node/v1","goal",goal,"capability",tool.name(),"schema",tool.parametersSchema(),"write",tool.writeOperation(),"inputBindings",bindings,"completionPolicy",tool.writeOperation()?"CONFIRMED_BUSINESS_RECEIPT":"TOOL_RESULT_OK","completionCriteria","真实工具回执"));bb.getPlan().add(n);
        }
        var db=app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);String initial=UUID.randomUUID().toString(),replacement=UUID.randomUUID().toString();
        for(String ref:List.of(initial,replacement))db.update("INSERT INTO agent_controlled_plan(id,org_id,user_id,conversation_id,tool_mode,goal,payload_json,expires_at,created_at) VALUES(?,1,1,?,'standard',?,?,?,?)",ref,ctx.getConversationId(),goal,AgentPreparedOperations.mapper().writeValueAsString(bb),java.time.LocalDateTime.now().plusMinutes(15),java.time.LocalDateTime.now());
        Long runId=app.getBean(com.pams.module.agent.orchestration.ControlledPlanStore.class).start(initial,ctx);
        app.getBean(com.pams.module.agent.orchestration.DagScheduler.class).drive(runId,1L);
        var node=app.getBean(com.pams.module.agent.orchestration.WorkItemState.class).nodes(runId).get(1);
        var message=app.getBean(ConversationStore.class).save(ctx.getConversationId(),com.pams.module.agent.entity.AgentMessage.ROLE_ASSISTANT,"按明确目标等待确认。",Sensitivity.NORMAL,"",null,null,ctx.getStreamId(),"fixture",0);
        message.setRunId(runId);app.getBean(ConversationStore.class).saveExecutionState(message,"WAITING_CONFIRM");
        rows.add(Map.of("kind","REPLAN","articleId",article.getId(),"taskId",node.getActionTaskId(),"nodeId",node.getId(),"runId",runId,"planRef",replacement,"conversationId",ctx.getConversationId()));
    }
    static ToolContext conversation(ConfigurableApplicationContext app,ToolContext base,String kind){
        var store=app.getBean(ConversationStore.class);var c=store.create(1L,"B6浏览器-"+kind);store.rename(c.getId(),1L,"B6浏览器-"+kind);var ctx=base.derive(c.getId(),"b6-browser-"+UUID.randomUUID());
        store.save(c.getId(),com.pams.module.agent.entity.AgentMessage.ROLE_USER,kind.equals("DOCX_REVISION")?"只把预算总额改为3000元，其余保留":"授权验收指定动作",Sensitivity.NORMAL,"",null,null,ctx.getStreamId(),"fixture",0);
        return ctx;
    }
    static AgentActionPlan persist(ConfigurableApplicationContext app,ToolContext ctx,String name,ToolResult result){
        var p=result.plan();if(p==null)throw new IllegalStateException(name+":"+result.summary());
        var saved=app.getBean(TaskGate.class).createPlan(ctx,app.getBean(ToolRegistry.class).find(name).orElseThrow(),p.summary(),p.total(),p.items(),p.skipped(),p.detailLines(),false,p.preview());
        app.getBean(ConversationStore.class).save(ctx.getConversationId(),com.pams.module.agent.entity.AgentMessage.ROLE_ASSISTANT,"已准备预览，等待确认。",Sensitivity.NORMAL,"",List.of(Map.of("tool",name,"status","OK","write",true)),null,ctx.getStreamId(),"fixture",0);return saved;
    }
}
