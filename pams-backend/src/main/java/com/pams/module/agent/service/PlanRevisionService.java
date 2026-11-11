package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.tool.ToolArgs;
import com.pams.module.archive.service.FileStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Snapshot-bound Word revision, version check and durable save receipt. */
@Service
public class PlanRevisionService {
    private static final String BLOCK_EDITS = "BLOCK_EDITS";
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private final JdbcTemplate db;
    private final PlanSnapshotStore snapshots;
    private final ActivityPlanRepository plans;
    private final ActivityRepository activities;
    private final PlanAccessService access;
    private final PlanAttachmentService attachments;
    private final PlanService planService;
    private final FileStorageService storage;
    private final AgentMessageRepository messages;
    private final AgentTaskRepository tasks;
    private final WordPageReview pageReview;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private static final Pattern GOAL=Pattern.compile("(?:请)?只(?:把|将)?(?:经费)?预算(?:总额)?(?:改成|改为|调整为)\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元[，,；;\\s]*(?:其余|其他)(?:全文)?(?:保持)?(?:不变|保留)[。！!\\s]*");
    private static final Pattern TOTAL=Pattern.compile("预算总额(?:为|[:：])\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元");
    public PlanRevisionService(JdbcTemplate db,PlanSnapshotStore snapshots,ActivityPlanRepository plans,ActivityRepository activities,
            PlanAccessService access,PlanAttachmentService attachments,PlanService planService,FileStorageService storage,
            AgentMessageRepository messages,AgentTaskRepository tasks, WordPageReview pageReview) {
        this.db=db;this.snapshots=snapshots;this.plans=plans;this.activities=activities;this.access=access;
        this.attachments=attachments;this.planService=planService;this.storage=storage;this.messages=messages;this.tasks=tasks;
        this.pageReview = pageReview;
    }
    private void writable(ToolContext ctx) {
        if(ctx==null || ctx.getLoginUser()==null || ctx.isReadonly() || ctx.isKb()
                || !ctx.hasPermission("activity:view") || !ctx.hasPermission("plan:view") || !ctx.hasPermission("plan:edit"))
            throw new BizException(403,"无权准备或提交策划书修订");
    }
    private ActivityPlan current(PlanSnapshotStore.Snapshot snapshot,ToolContext ctx,boolean lock) throws Exception {
        var p=plans.findById(snapshot.planId()).orElseThrow(()->new BizException(404,"策划书不存在或不可见"));
        if(lock) {activities.lockById(p.getActivityId()).orElseThrow(()->new BizException(404,"活动不存在或不可见"));entityManager.refresh(p);}
        access.requireOwner(p,ctx);
        if(p.getDocumentFileId()==null || p.getStatus()==ActivityPlan.PlanStatus.PENDING) throw new BizException(409,"仅支持当前非审核中的原生DOCX版本");
        byte[] current;
        try(var in=attachments.documentResource(p).getInputStream()) {current=in.readNBytes(1024*1024+1);}
        String baseline=PlanDocumentReader.hash(p.getId()+":"+p.getRevision()+":"+PlanDocumentReader.hash(current));
        if(!snapshot.baseline().equals(baseline)) throw new BizException(409,"STALE_BASELINE：原文已变化，请重新读取和预览");
        return p;
    }
    @Transactional
    public ToolResult prepare(ToolContext ctx,Map<String,Object> args) {
        try {
            writable(ctx);
            var snapshot=snapshots.require(ToolArgs.str(args,"documentRef"),ctx,true);
            if(!Objects.equals(ToolArgs.lng(args,"planId"),snapshot.planId()) || !snapshot.baseline().equals(ToolArgs.str(args,"baseline")))
                throw new BizException(409,"快照与指定版本不一致");
            var p=current(snapshot,ctx,false);
            var sources=messages.findByStreamIdAndRole(ctx.getStreamId(),com.pams.module.agent.entity.AgentMessage.ROLE_USER).stream()
                    .filter(m->Objects.equals(m.getConversationId(),ctx.getConversationId())).toList();
            if(sources.size()!=1) throw new BizException(409,"当前请求缺少可核验原始用户目标，请重新明确修订要求");
            var source=sources.getFirst();
            if (args.containsKey("edits")) return prepareBlockEdits(ctx, snapshot, p, source.getId(), args.get("edits"));
            if(!snapshot.eligible() || snapshot.covered()!=snapshot.total()) throw new BizException(409,"INSUFFICIENT_SOURCE：需要完整读取支持范围内的快照");
            var goal=GOAL.matcher(Objects.toString(source.getContent(),"").strip());
            if(!goal.matches()) throw new BizException(409,"首版仅支持明确要求：只把预算总额改为3000元，其余保留；其他范围请在原生编辑器修改");
            String blockId=ToolArgs.str(args,"blockId"),before=ToolArgs.str(args,"before"),after=ToolArgs.str(args,"after");
            var block=snapshot.blocks().stream().filter(b->Objects.equals(b.get("blockId"),blockId)).findFirst().orElseThrow(()->new BizException(409,"目标块不存在"));
            if(!Objects.equals(block.get("text"),before) || !Objects.equals(block.get("hash"),ToolArgs.str(args,"expectedHash")))
                throw new BizException(409,"目标旧值或指纹不匹配");
            validateChange(snapshot,before,after,goal.group(1));
            BudgetDocxRevision.replace(snapshot.bytes(),before,after); // Validate the exact native edit before preview.
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO agent_plan_revision(id,snapshot_id,org_id,user_id,plan_id,source_message_id,before_text,after_text,block_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id,snapshot.id(),ctx.getLoginUser().getOrgId(),ctx.getUserId(),p.getId(),source.getId(),before,after,blockId,LocalDateTime.now());
            String target="活动ID "+p.getActivityId()+" · 策划书ID "+p.getId()+" · 第"+p.getVersion()+"版";
            var plan=new AgentActionPlan(null,target+"预算修订（确认后创建新草稿）",1,
                    List.of(new AgentActionPlan.PlanItem(p.getId(),"预算总额",before+" → "+after)),List.of(),
                    List.of(target,"原文基线："+snapshot.baseline(),"仅采用以下单处变更；不提交审批、不更新活动信息。","__args__:{\"changeSetId\":\""+id+"\"}"),false,null,
                    "# 真实变更预览\n\n"+target+"\n\n原文：\n"+before+"\n\n修改为：\n"+after+"\n\n其余文档正文节点和所有其他DOCX部件保持原内容。确认后创建新草稿。");
            return ToolResult.plan("已准备预算修改预览，尚未写入策划书。请核对差异后确认。",plan);
        } catch(BizException e) {return ToolResult.failed(e.getMessage());}
        catch(Exception e) {return ToolResult.failed("不支持此文档的精确修订，请在原生编辑器修改");}
    }

    private List<WordBlockEdits.Edit> blockEdits(Object input) {
        if (!(input instanceof List<?> edits) || edits.isEmpty() || edits.size() > 12) {
            throw new BizException(409, "请提供 1 至 12 个文档块修订");
        }
        return edits.stream().map(edit -> json.convertValue(edit, WordBlockEdits.Edit.class)).toList();
    }

    private ToolResult prepareBlockEdits(ToolContext ctx, PlanSnapshotStore.Snapshot snapshot, ActivityPlan plan,
                                         Long sourceMessageId, Object input) throws Exception {
        var edits = blockEdits(input);
        WordBlockEdits.apply(snapshot, edits);
        String encoded = json.writeValueAsString(edits);
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) {
            throw new BizException(409, "本次修订过长，请分批修改");
        }
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO agent_plan_revision(id,snapshot_id,org_id,user_id,plan_id,source_message_id,before_text,after_text,block_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id, snapshot.id(), ctx.getLoginUser().getOrgId(), ctx.getUserId(), plan.getId(),
                sourceMessageId, "", encoded, BLOCK_EDITS, LocalDateTime.now());
        var items = edits.stream().map(edit -> new AgentActionPlan.PlanItem(plan.getId(), edit.blockId(), editSummary(edit))).toList();
        String preview = edits.stream().map(edit -> edit.blockId() + "\n\n" + editSummary(edit))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        var action = new AgentActionPlan(null, "策划书第 " + plan.getVersion() + " 版局部修订", items.size(), items,
                List.of(), List.of("确认后保存为新的草稿版本", "__args__:{\"changeSetId\":\"" + id + "\"}"), true, null, preview);
        return ToolResult.plan("已准备 Word 局部变更，等待确认保存草稿。", action);
    }

    private static String editSummary(WordBlockEdits.Edit edit) {
        return switch (edit.kind()) {
            case REPLACE -> "原文：" + edit.before() + "\n修改为：" + edit.after();
            case INSERT_BEFORE -> "在「" + edit.before() + "」之前插入：" + edit.after();
            case INSERT_AFTER -> "在「" + edit.before() + "」之后插入：" + edit.after();
            case DELETE -> "删除段落：" + edit.before();
        };
    }
    static void validateChange(PlanSnapshotStore.Snapshot snapshot,String before,String after,String target) {
        var amount=TOTAL.matcher(Objects.toString(before,""));
        if(!amount.find() || new java.math.BigDecimal(target).signum()<=0) throw new BizException(409,"无法定位唯一预算总额");
        String expected=before.substring(0,amount.start(1))+target+before.substring(amount.end(1));
        if(!expected.equals(after) || before.equals(after) || after.length()>600 || amount.find()) throw new BizException(409,"变更超出授权金额或没有实际变化");
        String all=snapshot.blocks().stream().map(b->b.get("text").toString()).reduce("",(a,b)->a+"\n"+b);
        if(Pattern.compile("[0-9]+(?:\\.[0-9]+)?\\s*元").matcher(all).results().count()!=1
                || snapshot.blocks().stream().anyMatch(b->!b.get("text").equals(before) && Pattern.compile("单价|分项|小计|合计|费用|物料|预算").matcher(b.get("text").toString()).find()))
            throw new BizException(409,"CONFLICT：存在分项金额或其他预算内容，需明确调整规则；本工具不会擅自修改分项");
    }
    @Transactional(rollbackFor=Exception.class)
    public WriteAgentTool.CommitResult commit(ToolContext input,AgentActionPlan action) throws Exception {
        writable(input);
        if(action==null || action.planId()==null) throw new BizException(403,"必须通过确认门禁提交");
        var task=tasks.findByIdAndUserId(action.planId(),input.getUserId()).orElseThrow(()->new BizException(404,"确认任务不存在"));
        if(!"preparePlanRevision".equals(task.getToolName()) || !"RUNNING".equals(task.getStatus())
                || !Objects.equals(task.getOrgId(),input.getLoginUser().getOrgId())) throw new BizException(403,"修订确认任务状态无效");
        var ctx=input.derive(task.getConversationId(),task.getStreamId());
        String id=null;
        for(String line:action.detailLines()) if(line.startsWith("__args__:"))
            id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.substring(9)).path("changeSetId").asText();
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw new BizException(409,"变更集无效");
        var rows=db.queryForList("SELECT * FROM agent_plan_revision WHERE id=? AND org_id=? AND user_id=? FOR UPDATE",id,ctx.getLoginUser().getOrgId(),ctx.getUserId());
        if(rows.size()!=1) throw new BizException(404,"变更集不存在或不可见");
        var change=rows.getFirst();
        var snapshot=snapshots.require(change.get("snapshot_id").toString(),ctx,true);
        // Even receipt lookup must still respect current access policy.
        var original=plans.findById(snapshot.planId()).orElseThrow(()->new BizException(404,"原版本不存在或不可见"));
        if(!access.canRead(original,ctx)) throw new BizException(403,"原版本已不可见");
        if(change.get("result_plan_id")!=null) {
            return receipt(((Number)change.get("result_plan_id")).longValue(), (String)change.get("page_review_json"));
        }
        var source=messages.findByIdAndConversationId(((Number)change.get("source_message_id")).longValue(),ctx.getConversationId())
                .filter(m->com.pams.module.agent.entity.AgentMessage.ROLE_USER.equals(m.getRole()) && Objects.equals(m.getStreamId(),ctx.getStreamId()))
                .orElseThrow(()->new BizException(409,"原始用户目标已不可核验，请重新预览"));
        current(snapshot,ctx,false);
        byte[] revised;
        if (BLOCK_EDITS.equals(change.get("block_id"))) {
            revised = WordBlockEdits.apply(snapshot, blockEdits(json.readValue(change.get("after_text").toString(), List.class)));
        } else {
            var goal = GOAL.matcher(Objects.toString(source.getContent(),"").strip());
            if (!goal.matches()) throw new BizException(409,"原始修订目标已变化，请重新预览");
            String before = change.get("before_text").toString(), after = change.get("after_text").toString();
            validateChange(snapshot,before,after,goal.group(1));
            revised = BudgetDocxRevision.replace(snapshot.bytes(),before,after);
        }
        var review = pageReview.inspectRevision(revised);
        String reviewJson = json.writeValueAsString(review.facts());
        // Rendering may take time. Revalidate the source under the activity lock immediately before saving.
        var p = current(snapshot,ctx,true);
        var file=storage.store(new BytesFile(revised),"PLAN",ctx.getUserId());
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status==STATUS_ROLLED_BACK) try {Files.deleteIfExists(storage.physicalPath(file.getPath()));} catch(IOException ignored) { /* orphan cleanup can retry */ }
            }
        });
        var req=new PlanRequest();req.setActivityId(p.getActivityId());req.setDocumentFileId(file.getId());req.setSyncActivity(false);
        // Preserve existing metadata overrides exactly; no activity synchronization.
        req.setNameOverride(p.getNameOverride());req.setThemeOverride(p.getThemeOverride());req.setTimeOverride(p.getTimeOverride());
        req.setLocationOverride(p.getLocationOverride());req.setOrganizerOverride(p.getOrganizerOverride());req.setTargetOverride(p.getTargetOverride());
        var saved=planService.createAgentNativeRevision(req,ctx,p.getId(),p.getRevision());
        db.update("UPDATE agent_plan_revision SET result_plan_id=?, page_review_json=? WHERE id=?",
                saved.getId(), reviewJson, id);
        return receipt(saved.getId(), reviewJson);
    }
    private WriteAgentTool.CommitResult receipt(Long id, String reviewJson) throws IOException {
        var p=plans.findById(id).orElseThrow(()->new BizException(409,"修订回执待核对"));
        var detail = ToolArgs.map("planId", id, "version", p.getVersion());
        if (reviewJson != null) detail.put("wordPageReview", json.readValue(reviewJson, Map.class));
        return new WriteAgentTool.CommitResult("SUCCESS",1,0,"已创建 Word 修订草稿，原稿保留。",detail,
                List.of(ToolArgs.map("type","activity","id",p.getActivityId(),"label","策划书修订草稿","link","/activities/"+p.getActivityId()+"?tab=plan&planId="+id)));
    }
    /** Read the durable business receipt independently of expired source snapshots. Never commits a revision. */
    @Transactional(readOnly=true)
    public Optional<WriteAgentTool.CommitResult> confirmedReceipt(ToolContext ctx,AgentActionPlan action) throws Exception {
        writable(ctx);
        String id=null;
        for(String line:action.detailLines()) if(line.startsWith("__args__:"))
            id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.substring(9)).path("changeSetId").asText();
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw new BizException(409,"变更集标识无效");
        var rows=db.queryForList("SELECT plan_id,result_plan_id,page_review_json FROM agent_plan_revision WHERE id=? AND org_id=? AND user_id=?",id,ctx.getLoginUser().getOrgId(),ctx.getUserId());
        if(rows.isEmpty() || rows.getFirst().get("result_plan_id")==null) return Optional.empty();
        var row=rows.getFirst();
        var source=plans.findById(((Number)row.get("plan_id")).longValue()).orElseThrow(()->new BizException(404,"回执关联版本不可见"));
        var result=plans.findById(((Number)row.get("result_plan_id")).longValue()).orElseThrow(()->new BizException(409,"业务回执对应版本待核对"));
        if(!access.canRead(source,ctx) || !access.canRead(result,ctx) || !Objects.equals(source.getActivityId(),result.getActivityId()))
            throw new BizException(403,"无权核对该版本回执");
        return Optional.of(receipt(result.getId(), (String)row.get("page_review_json")));
    }
    private record BytesFile(byte[] bytes) implements org.springframework.web.multipart.MultipartFile {
        public String getName(){return "document";}public String getOriginalFilename(){return "策划书修订草稿.docx";}
        public String getContentType(){return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
        public boolean isEmpty(){return bytes.length==0;}public long getSize(){return bytes.length;}
        public byte[] getBytes(){return bytes.clone();}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(File file) throws IOException {Files.write(file.toPath(),bytes);}
    }
}
