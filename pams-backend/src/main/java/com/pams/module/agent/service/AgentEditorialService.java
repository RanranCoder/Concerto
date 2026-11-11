package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.tool.ToolArgs;
import com.pams.module.content.entity.Article;
import com.pams.module.content.service.ArticleService;
import com.pams.module.content.dto.ArticleRequest;
import com.pams.module.activity.repository.ActivityPlanRepository;
import com.pams.module.activity.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Editorial preparation reuses native transitions. Human review/publish decisions are never tools here. */
@Service
public class AgentEditorialService {
    public static final Set<String> WRITES=Set.of("prepareArticleRevision","prepareArticleSubmission","preparePlanSubmission");
    private final AgentPreparedOperations ops;private final ArticleService articles;
    private final ActivityPlanRepository plans;private final PlanAccessService access;private final PlanService planService;
    public AgentEditorialService(AgentPreparedOperations ops,ArticleService articles,ActivityPlanRepository plans,PlanAccessService access,PlanService planService){this.ops=ops;this.articles=articles;this.plans=plans;this.access=access;this.planService=planService;}
    private Article article(ToolContext ctx,Long id,boolean editable){
        ops.guard(ctx,"content:view",false);if(id==null || id<=0)throw new BizException(400,"需要明确articleId");
        var a=articles.getEntity(id);if(!Objects.equals(a.getOrgId(),ctx.getLoginUser().getOrgId()))throw new BizException(404,"稿件不存在或不可见");
        if(editable && !Objects.equals(a.getAuthorId(),ctx.getUserId()) && !ctx.hasPermission("article:manage"))throw new BizException(403,"仅作者或稿件管理员可操作");
        if(editable && !Set.of(Article.ArticleStatus.DRAFT,Article.ArticleStatus.REJECTED).contains(a.getStatus()))throw new BizException(409,"仅草稿或驳回稿可修订或提交");return a;
    }
    private String baseline(Article a){return PlanDocumentReader.hash(ops.encode(ToolArgs.map("id",a.getId(),"revision",a.getRevision(),"title",a.getTitle(),"summary",a.getSummary(),"content",a.getContent(),"status",a.getStatus(),"author",a.getAuthorId())));}
    @Transactional(readOnly=true)
    public ToolResult readArticle(ToolContext ctx,Map<String,Object> args,boolean review){
        var a=article(ctx,integer(args,"articleId"),false);
        if(review)return ToolResult.ok("这是组织业务审批状态，与AI审校结果分开；审批通过也不代表已发布。",ToolArgs.map("articleId",a.getId(),"revision",a.getRevision(),"status",a.getStatus(),"reviewerId",a.getReviewerId(),"reviewComment",a.getReviewComment(),"link","/content/article?id="+a.getId(),"asOf",java.time.Instant.now().toString()));
        String expected=ToolArgs.str(args,"baseline");if(expected!=null&&!expected.equals(baseline(a)))throw new BizException(409,"稿件基线已变化");
        int length=Objects.toString(a.getTitle(),"").length()+Objects.toString(a.getSummary(),"").length()+Objects.toString(a.getContent(),"").length();
        if(length>16000)throw new BizException(409,"正文超过首版完整读取上限，请使用原生稿件编辑器；未返回摘要冒充全文");
        return ToolResult.ok("已读取稿件完整保存正文；HTML仅作为原文数据，附件正文未读取。",ToolArgs.map("articleId",a.getId(),"revision",a.getRevision(),"baseline",baseline(a),"title",a.getTitle(),"summary",a.getSummary(),"content",a.getContent(),"format","STORED_HTML","articleType",a.getArticleType(),"status",a.getStatus(),"authorId",a.getAuthorId(),"complete",true,"truncated",false,"scope","SAVED_BODY_EXCLUDING_ATTACHMENTS","asOf",java.time.Instant.now().toString()));
    }
    @Transactional(readOnly=true)
    public ToolResult planReview(ToolContext ctx,Long id){
        ops.guard(ctx,"plan:view",false);var p=plans.findById(id).filter(v->access.canRead(v,ctx)).orElseThrow(()->new BizException(404,"策划书不存在或不可见"));
        return ToolResult.ok("组织业务审批状态，与AI审校分开；不代替审批人决策。",ToolArgs.map("planId",p.getId(),"revision",p.getRevision(),"version",p.getVersion(),"status",p.getStatus(),"current",access.isCurrent(p),"reviewers",p.getReviewers().stream().map(r->ToolArgs.map("id",r.getUserId(),"name",r.getName(),"approved",r.getApproved(),"comment",r.getComment(),"reviewedAt",r.getReviewedAt())).toList(),"reviewerCandidates",ctx.hasPermission("plan:submit")&&Objects.equals(access.owner(p),ctx.getUserId())?access.candidates(ctx).stream().map(c->ToolArgs.map("id",c.id(),"name",c.name(),"deptName",c.deptName(),"roleName",c.roleName())).toList():List.of(),"asOf",java.time.Instant.now().toString()));
    }
    private Long integer(Map<String,Object> args,String key){Object value=args.get(key);if(value==null||!value.toString().matches("[0-9]{1,18}"))throw new BizException(400,"需要明确整数"+key);return Long.valueOf(value.toString());}
    @Transactional
    public ToolResult prepare(String kind,ToolContext ctx,Map<String,Object> args){
        String permission=switch(kind){case "preparePlanSubmission"->"plan:submit";case "prepareArticleSubmission"->"article:submit";default->"article:edit";};ops.guard(ctx,permission,true);
        var payload=new LinkedHashMap<String,Object>();String preview;
        if("preparePlanSubmission".equals(kind)){
            Long id=integer(args,"planId"),revision=integer(args,"expectedRevision");var p=plans.findById(id).orElseThrow(()->new BizException(404,"策划书不可见"));access.requireOwner(p,ctx);
            if(!Objects.equals(revision,p.getRevision()) || p.getStatus()!=com.pams.module.activity.entity.ActivityPlan.PlanStatus.DRAFT)throw new BizException(409,"策划书版本或状态已变化");
            var raw=args.get("reviewerIds");if(!(raw instanceof List<?> selected) || selected.isEmpty() || selected.size()>50)throw new BizException(400,"明确选择1–50位审核人");
            var reviewers=new ArrayList<Long>();var candidates=access.candidates(ctx);var selectedNames=new ArrayList<String>();
            for(Object value:selected){Long uid=integer(Map.of("id",value),"id");var candidate=candidates.stream().filter(c->c.id().equals(uid)).findFirst().orElseThrow(()->new BizException(403,"审核人已失效"));if(reviewers.contains(uid))throw new BizException(400,"审核人重复");reviewers.add(uid);selectedNames.add(candidate.name()+"（ID "+uid+"）");}
            payload.put("planId",id);payload.put("expectedRevision",revision);payload.put("reviewerIds",reviewers);preview="提交策划书版本 "+p.getVersion()+"（ID "+id+"，revision "+revision+"）给："+String.join("、",selectedNames)+"。确认只提交审核，不表示审批通过。";
        } else {
            var a=article(ctx,integer(args,"articleId"),true);String expected=ToolArgs.str(args,"baseline");if(expected==null||!expected.equals(baseline(a)))throw new BizException(409,"稿件基线已变化，请先读取完整原文");
            payload.put("articleId",a.getId());payload.put("expectedRevision",a.getRevision());payload.put("baseline",expected);
            if("prepareArticleRevision".equals(kind)){
                if(!(args.get("changes") instanceof Map<?,?> changes)||changes.isEmpty()||changes.size()>3)throw new BizException(400,"需要明确字段变更");
                var edited=new LinkedHashMap<String,String>();var lines=new ArrayList<String>();
                for(var entry:changes.entrySet()){
                    String field=entry.getKey().toString();if(!Set.of("title","summary","content").contains(field) || !(entry.getValue() instanceof Map<?,?> change) || !change.keySet().equals(Set.of("before","after")))throw new BizException(400,"仅支持标题、摘要、正文的明确单处文本替换");
                    String before=Objects.toString(change.get("before"),""),after=Objects.toString(change.get("after"),"");
                    String original=Objects.toString(switch(field){case "title"->a.getTitle();case "summary"->a.getSummary();default->a.getContent();},"");
                    if(before.isBlank()||before.length()>2000||after.length()>2000||before.matches("(?s).*[<>&].*")||after.matches("(?s).*[<>&].*"))throw new BizException(400,"仅支持不含HTML标记/实体的纯文本片段替换");
                    int at=original.indexOf(before);if(at<0||original.indexOf(before,at+before.length())>=0)throw new BizException(409,"原文片段缺失或重复，不能准确定位");
                    String replacement=original.substring(0,at)+after+original.substring(at+before.length());int max="title".equals(field)?150:"summary".equals(field)?500:16000;
                    if(replacement.isBlank()||replacement.length()>max)throw new BizException(400,"替换后字段为空或超限");
                    edited.put(field,replacement);lines.add(field+"：\n原文："+before+"\n替换："+after);
                }
                payload.put("edited",edited);preview="稿件「"+a.getTitle()+"」（ID "+a.getId()+"，revision "+a.getRevision()+"）\n\n"+String.join("\n\n",lines)+"\n\n仅保存当前草稿，保留负责人、活动、图片、期限与审批状态。";
            }else preview="提交稿件「"+a.getTitle()+"」（ID "+a.getId()+"，revision "+a.getRevision()+"）进入组织审核；不表示已通过或已发布。";
        }
        String label=switch(kind){case "preparePlanSubmission"->"策划书送审预览";case "prepareArticleSubmission"->"稿件送审预览";default->"稿件定向修订预览";};
        String id=ops.create(ctx,kind,payload);return ToolResult.plan("已准备预览，需用户确认。",ops.plan(id,label,List.of(new AgentActionPlan.PlanItem(null,"对象",Objects.toString(payload.getOrDefault("articleId",payload.get("planId"))))),preview,"不会自动审批或发布。"));
    }
    @Transactional(rollbackFor=Exception.class)
    public WriteAgentTool.CommitResult commit(String kind,ToolContext ctx,AgentActionPlan action){
        ops.guard(ctx,switch(kind){case "preparePlanSubmission"->"plan:submit";case "prepareArticleSubmission"->"article:submit";default->"article:edit";},true);
        var op=ops.require(ctx,action,kind,true);if(op.result()!=null){visibleReceipt(ctx,op.result());return op.result();}var p=op.payload();
        Map<String,Object> detail;
        if("preparePlanSubmission".equals(kind)){
            Long id=integer(p,"planId");var reviewers=((List<?>)p.get("reviewerIds")).stream().map(v->((Number)v).longValue()).toList();
            planService.submitAgent(id,ctx.getUserId(),reviewers,integer(p,"expectedRevision"));plans.flush();
            var saved=plans.findById(id).orElseThrow();detail=ToolArgs.map("planId",id,"activityId",saved.getActivityId(),"status","PENDING","revision",saved.getRevision(),"operationId",op.id());
        }else{
            var a=article(ctx,integer(p,"articleId"),true);if(!Objects.equals(p.get("baseline"),baseline(a)))throw new BizException(409,"稿件基线已变化");
            var request=new ArticleRequest();request.setTitle(a.getTitle());request.setSummary(a.getSummary());request.setContent(a.getContent());request.setCoverUrl(a.getCoverUrl());
            if(p.get("edited") instanceof Map<?,?> fields)for(var field:fields.entrySet())switch(field.getKey().toString()){case "title"->request.setTitle(field.getValue().toString());case "summary"->request.setSummary(field.getValue().toString());case "content"->request.setContent(field.getValue().toString());default->throw new BizException(409,"变更字段无效");}
            var saved=articles.agentChange(a.getId(),integer(p,"expectedRevision"),request,ctx.getUserId(),ctx.hasPermission("article:manage"),"prepareArticleSubmission".equals(kind));
            detail=ToolArgs.map("articleId",saved.getId(),"revision",saved.getRevision(),"status",saved.getStatus().name(),"operationId",op.id());
        }
        var references=detail.containsKey("planId")?List.of(ToolArgs.map("type","activity","id",detail.get("activityId"),"label","策划书送审版本","link","/activities/"+detail.get("activityId")+"?tab=plan&planId="+detail.get("planId"))):List.of(ToolArgs.map("type","article","id",detail.get("articleId"),"label","稿件","link","/content/articles?id="+detail.get("articleId")));
        var result=new WriteAgentTool.CommitResult("SUCCESS",1,0,"prepareArticleRevision".equals(kind)?"已保存稿件定向修订；未提交审核。":"已提交组织审核；等待人工审批，未发布。",detail,references);ops.finish(op.id(),result);return result;
    }
    private void visibleReceipt(ToolContext ctx,WriteAgentTool.CommitResult receipt){if(receipt.detail().containsKey("articleId"))article(ctx,((Number)receipt.detail().get("articleId")).longValue(),false);else planReview(ctx,((Number)receipt.detail().get("planId")).longValue());}
    @Transactional(readOnly=true)
    public Optional<WriteAgentTool.CommitResult> receipt(String kind,ToolContext ctx,AgentActionPlan action){var op=ops.require(ctx,action,kind,false);if(op.result()!=null)visibleReceipt(ctx,op.result());return Optional.ofNullable(op.result());}
}
