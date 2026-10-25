package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;
import java.util.*;

/** Immutable user-visible preview versions. Confirmation tokens and raw tool arguments never enter snapshots. */
@Service
public class ArtifactLedger {
    private final AgentArtifactRepository artifacts;
    private final AgentArtifactVersionRepository versions;
    private final AgentTaskRepository tasks;
    private final AgentRunRepository runs;
    private final AgentWorkItemRepository nodes;
    private final ToolRegistry tools;
    private final TenantAuthorizationService authorization;
    private final DraftContent drafts;
    @org.springframework.beans.factory.annotation.Autowired private AgentReviewIssueRepository reviewIssues;
    @org.springframework.beans.factory.annotation.Autowired private AgentConversationRepository conversations;
    private final ObjectMapper json=new ObjectMapper();
    public ArtifactLedger(AgentArtifactRepository artifacts,AgentArtifactVersionRepository versions,AgentTaskRepository tasks,
                          AgentRunRepository runs,AgentWorkItemRepository nodes,ToolRegistry tools,TenantAuthorizationService authorization,DraftContent drafts) {
        this.artifacts=artifacts;this.versions=versions;this.tasks=tasks;this.runs=runs;this.nodes=nodes;this.tools=tools;this.authorization=authorization;this.drafts=drafts;
    }
    @Transactional
    public void capture(AgentTask task,AgentActionPlan plan,Long lineage,String origin) {
        if(versions.findByTaskId(task.getId()).isPresent()) return;
        AgentArtifact artifact=lineage==null ? task.getWorkItemId()==null?null:artifacts.findByWorkItemId(task.getWorkItemId()).orElse(null) : artifacts.lock(lineage).orElseThrow();
        if(artifact==null) {
            artifact=new AgentArtifact();artifact.setUserId(task.getUserId());artifact.setOrgId(OrgContext.get());artifact.setRunId(task.getRunId());
            artifact.setWorkItemId(task.getWorkItemId());artifact.setConversationId(task.getConversationId());artifact.setToolName(task.getToolName());
            artifact.setTitle("NORMAL".equals(task.getDataSensitivity())?task.getSummary():"受限成果");
            artifact.setKind(plan.preview()!=null && !plan.preview().isBlank()?"TEXT":plan.items().size()>1?"TABLE":"ENTITY");artifact.setCreatedAt(LocalDateTime.now());
            artifact=artifacts.saveAndFlush(artifact);
        } else artifact=artifacts.lock(artifact.getId()).orElseThrow();
        if(!Objects.equals(task.getUserId(),artifact.getUserId()) || !Objects.equals(OrgContext.get(),artifact.getOrgId())) throw new BizException(403,"成果归属不匹配");
        artifact.setCurrentVersion(artifact.getCurrentVersion()+1);artifacts.save(artifact);
        var version=new AgentArtifactVersion();version.setArtifactId(artifact.getId());version.setVersion(artifact.getCurrentVersion());version.setTaskId(task.getId());
        version.setWorkItemRevision(task.getWorkItemRevision());version.setOrigin(origin);version.setCreatedAt(LocalDateTime.now());
        String review="NOT_REVIEWED";
        if(task.getWorkItemId()!=null) {
            var node=nodes.findById(task.getWorkItemId()).orElseThrow();version.setAttempt(node.getAttempt());review=node.getReviewStatus();
        }
        version.setReviewStatus(review);
        var sources=new ArrayList<Map<String,Object>>();
        if(task.getWorkItemId()!=null) {
            var node=nodes.findById(task.getWorkItemId()).orElseThrow();
            sources.add("NORMAL".equals(task.getDataSensitivity())?Map.of("label","任务要求","text",node.getInstruction()):Map.of("label","任务要求（受限）"));
            var all=nodes.findByRunIdOrderByNodeKeyAsc(task.getRunId());
            for(var key:tree(node.getDependenciesJson())) {
                var dependency=all.stream().filter(n->n.getNodeKey()==key.asInt()).findFirst();
                var source=new LinkedHashMap<String,Object>();source.put("label","上游步骤 "+key.asInt());
                dependency.flatMap(n->artifacts.findByWorkItemId(n.getId())).ifPresent(a->{source.put("artifactId",a.getId());source.put("version",a.getCurrentVersion());source.put("title",a.getTitle());});sources.add(source);
            }
        }
        version.setSourcesJson(encode(sources));
        if("NORMAL".equals(task.getDataSensitivity())) {
            version.setPreview(drafts.preview(task.getToolName(),plan));version.setFieldsJson(encode(drafts.fields(task.getToolName(),plan)));
            var visible=new LinkedHashMap<>(plan.toJson());visible.remove("confirmToken");visible.put("detailLines",plan.detailLines().stream().filter(l->l!=null && !l.startsWith("__args__:")).toList());
            visible.put("preview",version.getPreview());version.setPlanJson(encode(visible));
        } else {version.setPreview("请在对应业务页面核对受限内容。");version.setFieldsJson("{}");version.setPlanJson("{}");}
        versions.save(version);task.setArtifactId(artifact.getId());task.setArtifactVersion(version.getVersion());tasks.save(task);
    }
    public AgentArtifact require(Long id,ToolContext supplied) {
        var artifact=artifacts.findById(id).orElseThrow(()->new BizException(404,"成果不存在"));
        if(!Objects.equals(supplied.getUserId(),artifact.getUserId()) || !Objects.equals(OrgContext.get(),artifact.getOrgId())) throw new BizException(404,"成果不存在");
        if(artifact.getConversationId()!=null) {
            var conversation=conversations.findById(artifact.getConversationId()).orElseThrow(()->new BizException(404,"成果会话已删除"));
            if(conversation.getDeleted()!=null && conversation.getDeleted()!=0) throw new BizException(404,"成果会话已删除");
        }
        var current=authorization.refresh(supplied.withMode("standard"));
        var tool=tools.find(artifact.getToolName()).orElseThrow(()->new BizException(403,"成果能力已下线"));
        if(!tools.visibleTo(tool,current)) throw new BizException(403,"成果所需权限已变化");
        if(artifact.getRunId()!=null) {
            var run=runs.findByIdAndUserId(artifact.getRunId(),supplied.getUserId()).orElseThrow(()->new BizException(403,"成果归属已变化"));
            var required=tree(run.getAuthorityJson());
            for(var permission:required) if(!current.hasPermission(permission.asText())) throw new BizException(403,"任务权限已变化");
        }
        return artifact;
    }
    public AgentArtifactVersion version(Long id,int number) {return versions.findByArtifactIdAndVersion(id,number).orElseThrow(()->new BizException(404,"成果版本不存在"));}
    @Transactional
    public AgentArtifact assertCurrent(Long id,int expected,ToolContext ctx) {
        require(id,ctx);var a=artifacts.lock(id).orElseThrow();
        if(a.getCurrentVersion()!=expected) throw new BizException(409,"成果已有新版本，请保留修改并重新核对");return a;
    }
    public List<Map<String,Object>> listRun(Long id) {return artifacts.findByRunIdOrderByIdAsc(id).stream().map(this::summary).toList();}
    public List<Map<String,Object>> listConversation(Long id,ToolContext ctx) {
        return artifacts.findByConversationIdAndUserIdOrderByIdAsc(id,ctx.getUserId()).stream().filter(a->Objects.equals(a.getOrgId(),OrgContext.get())).map(a->{require(a.getId(),ctx);return summary(a);}).toList();
    }
    public Map<String,Object> summary(AgentArtifact artifact) {
        var version=version(artifact.getId(),artifact.getCurrentVersion());var task=tasks.findById(version.getTaskId()).orElseThrow();
        var out=new LinkedHashMap<String,Object>();out.put("id",artifact.getId());out.put("title",artifact.getTitle());out.put("kind",artifact.getKind());
        out.put("currentVersion",artifact.getCurrentVersion());out.put("taskId",task.getId());out.put("runId",artifact.getRunId());out.put("workItemId",artifact.getWorkItemId());
        out.put("status",task.getStatus());out.put("taskVersion",task.getRowVersion());out.put("toolName",artifact.getToolName());return out;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(Long id,Integer number,ToolContext ctx) {
        var artifact=require(id,ctx);var selected=version(id,number==null?artifact.getCurrentVersion():number);
        var task=tasks.findById(selected.getTaskId()).orElseThrow();
        var out=new LinkedHashMap<String,Object>();out.put("artifact",summary(artifact));
        out.put("versions",versions.findByArtifactIdOrderByVersionDesc(id,PageRequest.of(0,100)).stream().map(v->Map.of("version",v.getVersion(),"origin",v.getOrigin(),"createdAt",v.getCreatedAt(),"taskId",v.getTaskId())).toList());
        out.put("version",selected.getVersion());out.put("origin",selected.getOrigin());out.put("preview",selected.getPreview());out.put("fields",object(selected.getFieldsJson()));
        out.put("plan",object(selected.getPlanJson()));out.put("reviewStatus",selected.getReviewStatus());out.put("status",task.getStatus());out.put("taskId",task.getId());
        boolean editable=selected.getVersion()==artifact.getCurrentVersion() && Set.of("PENDING_CONFIRM","REVIEW_REQUIRED").contains(task.getStatus()) && drafts.editable(task.getToolName()) && "NORMAL".equals(task.getDataSensitivity());
        if(artifact.getRunId()!=null) {
            var run=runs.findById(artifact.getRunId()).orElseThrow();editable=editable && !run.isCancelRequested() && run.getDispatchToken()==null;
        }
        out.put("editable",editable);out.put("artifacts",TaskGate.toArtifacts(task.getResultJson()==null?null:object(task.getResultJson())));
        out.put("sources",selected.getSourcesJson()==null?List.of():tree(selected.getSourcesJson()));
        out.put("sourceNotice","本次任务与业务工具的读取结果；没有外部出处时不推造引用。");
        if(artifact.getRunId()!=null) out.put("issues",reviewIssues.findByRunIdOrderByIdAsc(artifact.getRunId()).stream().filter(i->Objects.equals(i.getWorkItemId(),artifact.getWorkItemId()) && Objects.equals(i.getRevision(),selected.getWorkItemRevision()) && Objects.equals(i.getAttempt(),selected.getAttempt())).map(i->Map.of("id",i.getId(),"severity",i.getSeverity(),"location",i.getLocation(),"quote",i.getQuoteText(),"reason",i.getReason())).toList());
        else out.put("issues",List.of());
        return out;
    }
    public Map<String,Object> object(String value) {return value==null?Map.of():readObject(value);}
    private com.fasterxml.jackson.databind.JsonNode tree(String value) {try {return AgentJson.readTree(json,value);} catch(Exception e) {throw new BizException(4400,"成果来源记录损坏，请刷新后核对");}}
    private Map<String,Object> readObject(String value) {try {return AgentJson.readObject(json,value);} catch(Exception e) {throw new BizException(4400,"成果内容记录损坏，请刷新后核对");}}
    private String encode(Object value) {try {return json.writeValueAsString(value);} catch(Exception e) {throw new IllegalStateException(e);}}
}
