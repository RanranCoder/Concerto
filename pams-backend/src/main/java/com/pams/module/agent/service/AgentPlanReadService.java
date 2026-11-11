package com.pams.module.agent.service;

import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.tool.ToolArgs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class AgentPlanReadService {
    @org.springframework.beans.factory.annotation.Autowired
    private PlanSnapshotStore snapshots;
    private final ActivityRepository activities;
    private final ActivityPlanRepository plans;
    private final PlanAccessService access;
    private final PlanAttachmentService attachments;
    public AgentPlanReadService(ActivityRepository activities,ActivityPlanRepository plans,PlanAccessService access,PlanAttachmentService attachments) {
        this.activities=activities;this.plans=plans;this.access=access;this.attachments=attachments;
    }
    private boolean authorized(ToolContext ctx,boolean plan) {
        return ctx!=null && ctx.getLoginUser()!=null && ctx.hasPermission("activity:view") && (!plan || ctx.hasPermission("plan:view"));
    }
    private Activity activity(Long id,ToolContext ctx) {
        if(!authorized(ctx,false)) return null;
        return activities.findById(id).filter(a->Objects.equals(a.getOrgId(),ctx.getLoginUser().getOrgId())).orElse(null);
    }
    public ToolResult detail(ToolContext ctx,Long id) {
        if(id==null || id<=0) return error("VALIDATION_ERROR","需要明确 activityId");
        var a=activity(id,ctx);if(a==null) return invisible();
        return ToolResult.ok("已读取活动详情；不包含成员和敏感子模块。",ToolArgs.map(
                "activityId",a.getId(),"name",a.getName(),"theme",a.getTheme(),"status",a.getStatus(),
                "startDate",a.getStartDate(),"endDate",a.getEndDate(),"location",a.getLocation(),
                "organizer",a.getOrganizer(),"updatedAt",a.getUpdatedAt(),"revision",null,
                "revisionAvailable",false,"asOf",Instant.now().toString()));
    }
    public ToolResult versions(ToolContext ctx,Long id,int page) {
        if(id==null || id<=0 || page<0 || page>10000) return error("VALIDATION_ERROR","需要有效 activityId 和 page");
        if(!authorized(ctx,true) || activity(id,ctx)==null) return invisible();
        // Filter before pagination and counting. Never expose hidden versions or their count.
        var visible=plans.findByActivityIdOrderByVersionDesc(id).stream().filter(p->access.canRead(p,ctx))
                .sorted(Comparator.comparing(ActivityPlan::getVersion,Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ActivityPlan::getId,Comparator.reverseOrder())).toList();
        int start=Math.min(page*5,visible.size()),end=Math.min(start+5,visible.size());
        var names=access.authorNames(visible.subList(start,end),ctx);
        var rows=visible.subList(start,end).stream().map(p->ToolArgs.map("planId",p.getId(),"version",p.getVersion(),
                "status",p.getStatus(),"revision",p.getRevision(),"format",p.getDocumentFileId()==null?"LEGACY":"DOCX",
                "authorId",access.owner(p),"authorName",names.get(access.owner(p)),
                "updatedAt",p.getUpdatedAt(),"availableActions",List.of("READ"))).toList();
        return ToolResult.ok("已读取可见策划书版本；按版本倒序，最新可见不等于最新已通过版本。",ToolArgs.map(
                "activityId",id,"versions",rows,"page",page,"pageSize",5,"total",visible.size(),"hasMore",end<visible.size(),
                "scope","VISIBLE_VERSIONS","asOf",Instant.now().toString()));
    }
    @Transactional
    public ToolResult document(ToolContext ctx,Long id,String expected,int offset) {
        if(id==null || id<=0 || offset<0) return error("VALIDATION_ERROR","需要有效 planId 与 cursor");
        if(offset>0 && (expected==null || expected.isBlank())) return error("VALIDATION_ERROR","续读必须携带上一页 baseline");
        if(!authorized(ctx,true)) return invisible();
        var plan=plans.findById(id).filter(p->access.canRead(p,ctx)).orElse(null);
        if(plan==null || activity(plan.getActivityId(),ctx)==null) return invisible();
        try {
            byte[] bytes; PlanDocumentReader.Document doc;
            if(plan.getDocumentFileId()!=null) {
                try(var in=attachments.documentResource(plan).getInputStream()) {bytes=in.readNBytes(20*1024*1024+1);}
                if(bytes.length>20*1024*1024) return error("BUDGET_EXCEEDED","文档超过20MB读取上限");
                doc=PlanDocumentReader.read(bytes);
            } else {
                // Preserve exact legacy HTML fields. Do not pretend derived plain text is a DOCX.
                var fields=ToolArgs.map("background",plan.getBackground(),"purpose",plan.getPurpose(),"content",plan.getContent(),
                        "flow",plan.getFlow(),"notice",plan.getNotice(),"emergency",plan.getEmergency(),"budget",plan.getBudget(),
                        "sectionOrder",plan.getSectionOrder(),"nameOverride",plan.getNameOverride(),"themeOverride",plan.getThemeOverride(),
                        "timeOverride",plan.getTimeOverride(),"locationOverride",plan.getLocationOverride(),
                        "organizerOverride",plan.getOrganizerOverride(),"targetOverride",plan.getTargetOverride());
                bytes=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(fields);
                var blocks=new ArrayList<Map<String,Object>>();
                for(var field:fields.entrySet()) if(field.getValue()!=null) {
                    String text=field.getValue().toString();
                    if(text.length()>2_000_000) return error("BUDGET_EXCEEDED","旧文档字段超过读取上限");
                    for(int start=0,part=0;start<Math.max(1,text.length());start+=600,part++) {
                        String piece=text.substring(start,Math.min(start+600,text.length()));
                        blocks.add(Map.of("blockId",field.getKey()+"p"+part,"type","LEGACY_HTML","text",piece,"hash",PlanDocumentReader.hash(piece)));
                        if(blocks.size()>10000) return error("BUDGET_EXCEEDED","文档块数超过读取上限");
                    }
                }
                doc=new PlanDocumentReader.Document(blocks,List.of("LEGACY_HTML_NOT_NATIVE_DOCX"));
            }
            String baseline=PlanDocumentReader.hash(id+":"+plan.getRevision()+":"+PlanDocumentReader.hash(bytes));
            if(expected!=null && !expected.isBlank() && !baseline.equals(expected)) return error("STALE_BASELINE","文档已变更，请从第一页重读");
            if(offset>doc.blocks().size()) return error("VALIDATION_ERROR","cursor 超出文档范围");
            int end=Math.min(offset+4,doc.blocks().size());
            var snapshot=snapshots==null?null:snapshots.record(ctx,id,baseline,bytes,doc,offset,end);
            return ToolResult.ok("已读取指定版本原文页。局部修订只能修改已读取的 Word 块；预算校验模式需要完整覆盖。",ToolArgs.map(
                    "planId",id,"versionLabel",plan.getVersion(),"baseline",baseline,
                    "documentRef",snapshot==null?null:snapshot.id(),
                    "blocks",doc.blocks().subList(offset,end),"nextCursor",end<doc.blocks().size()?end:null,
                    "completeness",ToolArgs.map("scope","DOCUMENT_BODY","complete",offset==0 && end==doc.blocks().size() && doc.unsupportedElements().isEmpty(),
                            "hasMore",end<doc.blocks().size(),"truncated",false,"returnedFrom",offset,"returnedTo",end,"totalBlocks",doc.blocks().size()),
                    "unsupportedElements",doc.unsupportedElements(),
                    "revisionEligible",snapshot!=null && snapshot.eligible() && snapshot.covered()==snapshot.total(),
                    "coveredThrough",snapshot==null?null:snapshot.covered(),"asOf",Instant.now().toString()));
        } catch(Exception e) {return error("UNSUPPORTED_FORMAT","无法可靠读取该文档；请使用原生编辑器查看");}
    }
    private ToolResult invisible() {return error("NOT_FOUND_OR_NOT_VISIBLE","对象不存在或不可见");}
    private ToolResult error(String category,String message) {
        return new ToolResult("FAILED",message,List.of(),null,Map.of("errorCategory",category,"retryable",false),message,null);
    }
}
