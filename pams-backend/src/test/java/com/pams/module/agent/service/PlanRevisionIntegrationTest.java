package com.pams.module.agent.service;

import com.pams.common.OrgContext;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.PlanAttachmentService;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.tool.PreparePlanRevisionTool;
import com.pams.module.archive.service.FileStorageService;
import com.pams.security.LoginUser;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="pams.upload-dir=target/batch4-test-files")
@ActiveProfiles("test")
@Transactional
class PlanRevisionIntegrationTest {
    @Autowired AgentPlanReadService reads;
    @Autowired PlanRevisionService revisions;
    @Autowired PlanSnapshotStore snapshots;
    @Autowired PreparePlanRevisionTool tool;
    @Autowired ActivityRepository activities;
    @Autowired ActivityPlanRepository plans;
    @Autowired AgentConversationRepository conversations;
    @Autowired AgentMessageRepository messages;
    @Autowired AgentTaskRepository tasks;
    @Autowired FileStorageService storage;
    @Autowired PlanAttachmentService attachments;
    @Autowired JdbcTemplate db;
    @Autowired TaskGate gate;
    @Autowired ToolOutcomeHandler outcomes;
    @Autowired com.pams.module.agent.tool.DraftPlanSectionsTool draftTool;
    @Autowired ActionReconciliationService reconciliation;
    @Autowired com.pams.module.agent.orchestration.WorkItemState workItems;
    @Autowired com.pams.module.agent.orchestration.AgentRunService runs;
    ToolContext ctx;ActivityPlan original;byte[] source;
    @BeforeEach void setup() throws Exception {
        OrgContext.set(1L);
        var conversation=new AgentConversation();conversation.setUserId(1L);conversation.setTitle("预算修订验证");conversation.setCreatedAt(java.time.LocalDateTime.now());conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversation=conversations.saveAndFlush(conversation);
        ctx=new ToolContext(new LoginUser(1L,"teacher","测试","TEACHER",5,null,null,1L),
                List.of("activity:view","plan:view","plan:edit").stream().map(SimpleGrantedAuthority::new).toList(),conversation.getId(),"revision-"+UUID.randomUUID());
        var userMessage=new AgentMessage();userMessage.setConversationId(conversation.getId());userMessage.setStreamId(ctx.getStreamId());
        userMessage.setRole(AgentMessage.ROLE_USER);userMessage.setContent("只把预算总额改为3000元，其余保留");userMessage.setCreatedAt(java.time.LocalDateTime.now());messages.saveAndFlush(userMessage);
        var a=new Activity();a.setName("预算验证");a.setType("OTHER");a.setStatus(ActivityStatus.ASSIGNED);a.setStartDate(java.time.LocalDate.now());a.setCreatedAt(java.time.LocalDateTime.now());a.setCreatedBy(1L);a.setDeleted(0);a=activities.saveAndFlush(a);
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("不改此段落");
            var run=doc.createParagraph().createRun();run.setBold(true);run.setText("预算总额为2500元");
            doc.write(out);source=out.toByteArray();
        }
        var file=storage.store(new MemoryFile(source),"PLAN",1L);
        original=new ActivityPlan();original.setActivityId(a.getId());original.setVersion(1);original.setOwnerId(1L);original.setStatus(ActivityPlan.PlanStatus.DRAFT);
        original.setDocumentFileId(file.getId());original.setCreatedAt(java.time.LocalDateTime.now());original=plans.saveAndFlush(original);
    }
    @AfterEach void clear() throws Exception {
        // Committed concurrency fixtures are removed by exact generated IDs; ordinary tests roll back.
        if(original!=null && !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            var fileIds=db.queryForList("SELECT document_file_id FROM activity_plan WHERE activity_id=?",Long.class,original.getActivityId());
            db.update("DELETE FROM agent_plan_revision WHERE snapshot_id IN (SELECT id FROM agent_plan_snapshot WHERE conversation_id=?)",ctx.getConversationId());
            db.update("DELETE FROM agent_plan_snapshot WHERE conversation_id=?",ctx.getConversationId());
            db.update("DELETE FROM agent_task WHERE conversation_id=?",ctx.getConversationId());
            db.update("DELETE FROM agent_message WHERE conversation_id=?",ctx.getConversationId());
            db.update("DELETE FROM agent_conversation WHERE id=?",ctx.getConversationId());
            db.update("DELETE FROM activity_plan WHERE activity_id=?",original.getActivityId());
            db.update("DELETE FROM activity WHERE id=?",original.getActivityId());
            for(Long id:fileIds) {
                var paths=db.queryForList("SELECT path FROM file_record WHERE id=?",String.class,id);
                for(String path:paths) Files.deleteIfExists(storage.physicalPath(path));
                db.update("DELETE FROM file_record WHERE id=?",id);
            }
        }
        OrgContext.clear();
    }
    Map<String,Object> args() {
        var read=reads.document(ctx,original.getId(),null,0);assertThat(read.isOk()).as(read.summary()).isTrue();
        assertThat(read.payload()).containsEntry("revisionEligible",true);
        @SuppressWarnings("unchecked") var blocks=(List<Map<String,Object>>)read.payload().get("blocks");
        var block=blocks.stream().filter(b->b.get("text").toString().contains("预算")).findFirst().orElseThrow();
        return Map.of("planId",original.getId(),"documentRef",read.payload().get("documentRef"),"baseline",read.payload().get("baseline"),
                "blockId",block.get("blockId"),"expectedHash",block.get("hash"),"before",block.get("text"),"after","预算总额为3000元");
    }
    AgentActionPlan task(AgentActionPlan raw) {
        var task=new AgentTask();task.setUserId(1L);task.setConversationId(ctx.getConversationId());task.setStreamId(ctx.getStreamId());
        task.setToolName(tool.name());task.setStatus("RUNNING");task.setCreatedAt(java.time.LocalDateTime.now());task=tasks.saveAndFlush(task);
        if(ctx.getWorkItemId()!=null) {
            task.setRunId(ctx.getRunId());task.setWorkItemId(ctx.getWorkItemId());task.setWorkItemRevision(ctx.getWorkItemRevision());
            task=tasks.saveAndFlush(task);workItems.attach(task);
        }
        try {task.setPlanJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw.toJson()));tasks.saveAndFlush(task);}
        catch(Exception e){throw new IllegalStateException(e);}
        return new AgentActionPlan(task.getId(),raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,"test-token",raw.preview());
    }
    @Test void previewDoesNotWriteAndConfirmedNativeRevisionPreservesOriginalAndReturnsSameReceipt() throws Exception {
        long count=plans.count();var prepared=tool.execute(ctx,args());assertThat(prepared.plan()).as(prepared.summary()).isNotNull();
        assertThat(plans.count()).isEqualTo(count);assertThat(prepared.plan().preview()).contains("2500","3000");
        var action=task(prepared.plan());
        var first=revisions.commit(ctx.derive(null,null),action);
        var second=revisions.commit(ctx.derive(null,null),action);
        assertThat(first.detail()).isEqualTo(second.detail());assertThat(plans.count()).isEqualTo(count+1);
        try(var in=attachments.documentResource(original).getInputStream()){assertThat(in.readAllBytes()).isEqualTo(source);}
        var revised=plans.findById(((Number)first.detail().get("planId")).longValue()).orElseThrow();
        assertThat(revised.getStatus()).isEqualTo(ActivityPlan.PlanStatus.DRAFT);assertThat(revised.getDocumentFileId()).isNotEqualTo(original.getDocumentFileId());
        try(var in=attachments.documentResource(revised).getInputStream();var doc=new XWPFDocument(in)) {
            assertThat(doc.getParagraphs().getFirst().getText()).isEqualTo("不改此段落");
            assertThat(doc.getParagraphs().get(1).getText()).isEqualTo("预算总额为3000元");
            assertThat(doc.getParagraphs().get(1).getRuns().getFirst().isBold()).isTrue();
        }
    }
    @Test void delegatedWordDraftReturnsSavedReceiptThroughTheSharedOutcomeHandler() throws Exception {
        var input = Map.<String,Object>of("activityId", original.getActivityId(), "saveDraft", true,
                "documentBody", "<h1>培训班策划</h1><p>会务分工</p><table><tr><td>物料</td><td>2500 元</td></tr></table>");
        var prepared = draftTool.execute(ctx, input);
        assertThat(prepared.isOk()).as(prepared.summary()).isTrue();
        var outcome = outcomes.prepare(ctx, draftTool, prepared,
                new com.pams.module.agent.llm.LlmProvider.ToolCallRequest("word-draft", draftTool.name(), input));
        assertThat(outcome.waiting()).isFalse();
        assertThat(outcome.receipt()).containsEntry("status", "SUCCESS");
        var revised = plans.findTopByActivityIdOrderByVersionDesc(original.getActivityId()).orElseThrow();
        assertThat(revised.getVersion()).isEqualTo(original.getVersion() + 1);
        assertThat(revised.getStatus()).isEqualTo(ActivityPlan.PlanStatus.DRAFT);
        try (var in = attachments.documentResource(revised).getInputStream(); var doc = new XWPFDocument(in)) {
            assertThat(doc.getTables().getFirst().getRow(0).getCell(1).getText()).isEqualTo("2500 元");
        }
    }

    @Test void generalBlockEditUsesTheExistingVersionAndReceiptPath() throws Exception {
        var base = args();
        var sourceMessage = messages.findByStreamIdAndRole(ctx.getStreamId(), AgentMessage.ROLE_USER).getFirst();
        sourceMessage.setContent("把策划书中的预算总额改称经费上限");
        messages.saveAndFlush(sourceMessage);
        var input = Map.<String,Object>of("planId",base.get("planId"),"documentRef",base.get("documentRef"),"baseline",base.get("baseline"),
                "edits",List.of(Map.of("blockId",base.get("blockId"),"expectedHash",base.get("expectedHash"),
                        "before","预算总额","after","经费上限")));
        var prepared = tool.execute(ctx,input);
        assertThat(prepared.isOk()).as(prepared.summary()).isTrue();
        var result = revisions.commit(ctx,task(prepared.plan()));
        assertThat(result.status()).isEqualTo("SUCCESS");
        var revised = plans.findById(((Number)result.detail().get("planId")).longValue()).orElseThrow();
        try (var in = attachments.documentResource(revised).getInputStream(); var doc = new XWPFDocument(in)) {
            assertThat(doc.getParagraphs()).extracting(XWPFParagraph::getText).containsExactly("不改此段落","经费上限为2500元");
        }
    }

    @Test void staleBaselineOrMissingPermissionCannotPrepareOrSubmit() {
        var args=args();var prepared=tool.execute(ctx,args);var action=task(prepared.plan());
        original.setNotice("并发更新");plans.saveAndFlush(original);
        assertThat(tool.execute(ctx,args).plan()).isNull();
        assertThatThrownBy(()->revisions.commit(ctx,action)).hasMessageContaining("STALE_BASELINE");
        var readonly=ctx.withMode("readonly");assertThat(tool.execute(readonly,args).plan()).isNull();
    }
    @Test void missingPermissionAndCancellationBlockTheWrite() throws Exception {
        var arguments=args();var prepared=tool.execute(ctx,arguments);var action=task(prepared.plan());
        var low=new ToolContext(ctx.getLoginUser(),List.of(new SimpleGrantedAuthority("activity:view")),ctx.getConversationId(),ctx.getStreamId());
        assertThat(tool.execute(low,arguments).plan()).isNull();
        assertThatThrownBy(()->revisions.commit(low,action)).hasMessageContaining("无权");
        var cancelled=tasks.findById(action.planId()).orElseThrow();cancelled.setStatus("CANCELLED");tasks.saveAndFlush(cancelled);
        assertThatThrownBy(()->revisions.commit(ctx,action)).hasMessageContaining("状态无效");
    }
    @Test void alteredScopeAndCrossUserSnapshotAreRejected() {
        var args=new HashMap<>(args());args.put("after","预算总额为9000元");
        assertThat(tool.execute(ctx,args).plan()).isNull();
        var other=new ToolContext(new LoginUser(2L,"other","其他人","TEACHER",5,null,null,1L),List.of(),ctx.getConversationId(),ctx.getStreamId());
        assertThatThrownBy(()->snapshots.require(args.get("documentRef").toString(),other,false)).hasMessageContaining("不可见");
    }
    @Test void originalGoalIsRevalidatedAtConfirmation() {
        var prepared=tool.execute(ctx,args());var action=task(prepared.plan());
        var source=messages.findByStreamIdAndRole(ctx.getStreamId(),AgentMessage.ROLE_USER).getFirst();
        source.setContent("只把预算总额改为4000元，其余保留");messages.saveAndFlush(source);
        assertThatThrownBy(()->revisions.commit(ctx,action)).hasMessageContaining("超出授权");
    }
    @Test void reconciliationUsesCommittedReceiptAfterSnapshotExpiryAndNeverCreatesAnotherVersion() throws Exception {
        var prepared=tool.execute(ctx,args());var action=task(prepared.plan());
        var committed=revisions.commit(ctx,action);long count=plans.count();
        assertThat(committed.detail()).containsKey("wordPageReview");
        tasks.finish(action.planId(),"RESULT_UNKNOWN","{\"status\":\"RESULT_UNKNOWN\"}",java.time.LocalDateTime.now());
        db.update("DELETE FROM agent_plan_snapshot WHERE conversation_id=?",ctx.getConversationId());
        assertThat(revisions.confirmedReceipt(ctx,action).orElseThrow().detail()).isEqualTo(committed.detail());
        var recovered=reconciliation.reconcile(action.planId(),ctx.derive(null,null));
        assertThat(recovered).containsEntry("status","SUCCESS").containsEntry("reconciled",true);
        assertThat(recovered.toString()).contains(committed.detail().get("planId").toString());
        reconciliation.reconcile(action.planId(),ctx.derive(null,null));assertThat(plans.count()).isEqualTo(count);
    }
    @Test void missingReceiptStaysUnknownAndCannotBeQueriedByAnotherUserOrReadonlyMode() throws Exception {
        var prepared=tool.execute(ctx,args());var action=task(prepared.plan());long count=plans.count();
        tasks.finish(action.planId(),"RESULT_UNKNOWN",null,java.time.LocalDateTime.now());
        assertThat(reconciliation.reconcile(action.planId(),ctx)).containsEntry("reconciled",false).containsEntry("reconciliationState","RESULT_UNKNOWN");
        assertThat(tasks.findById(action.planId()).orElseThrow().getStatus()).isEqualTo("RESULT_UNKNOWN");
        assertThat(plans.count()).isEqualTo(count);
        assertThatThrownBy(()->reconciliation.reconcile(action.planId(),ctx.withMode("readonly"))).hasMessageContaining("权限或模式");
        var other=new ToolContext(new LoginUser(2L,"other","其他人","TEACHER",5,null,null,1L),List.of(),null,null);
        assertThatThrownBy(()->reconciliation.reconcile(action.planId(),other)).hasMessageContaining("不可见");
        var otherOrg=new ToolContext(new LoginUser(1L,"teacher","测试","TEACHER",5,null,null,2L),List.of(),null,null);
        assertThatThrownBy(()->reconciliation.reconcile(action.planId(),otherOrg)).hasMessageContaining("不可见");
    }
    @Test void interruptedPriorProcessCanBeSettledFromReceiptWithoutReplayingCommit() throws Exception {
        var prepared=tool.execute(ctx,args());var action=task(prepared.plan());revisions.commit(ctx,action);
        long count=plans.count();var old=tasks.findById(action.planId()).orElseThrow();
        old.setConfirmedAt(com.pams.module.agent.orchestration.RuntimeIdentity.STARTED.minusSeconds(1));old.setExecutionOwner("previous-process");tasks.saveAndFlush(old);
        assertThat(reconciliation.reconcile(action.planId(),ctx)).containsEntry("reconciled",true);
        assertThat(plans.count()).isEqualTo(count);
    }
    @Test void verifiedReceiptUnlocksDownstreamAndProvidesBusinessFacts() throws Exception {
        var run=runs.startRun(ctx.getConversationId(),1L,"修订后接续","P1");
        var bb=new com.pams.module.agent.orchestration.Blackboard();
        var first=new com.pams.module.agent.orchestration.Blackboard.PlanItem();first.setId(1);first.setAgent("planning");first.setTask("修改预算");first.setDependsOn(List.of());
        var next=new com.pams.module.agent.orchestration.Blackboard.PlanItem();next.setId(2);next.setAgent("retrieval");next.setTask("核对已保存版本");next.setDependsOn(List.of(1));bb.getPlan().addAll(List.of(first,next));
        workItems.initialize(run.getId(),bb,ctx);workItems.acquire(run.getId(),"test-lease");
        var node=workItems.begin(run.getId(),workItems.nodes(run.getId()).getFirst().getId(),"test-lease");
        ctx=ctx.withRunId(run.getId()).withWorkItem(node.getId(),node.getRevision());
        var prepared=tool.execute(ctx,args());var action=task(prepared.plan());revisions.commit(ctx,action);
        tasks.finish(action.planId(),"RESULT_UNKNOWN",null,java.time.LocalDateTime.now());workItems.settle(tasks.findById(action.planId()).orElseThrow());
        assertThat(workItems.ready(run.getId())).isEmpty();
        assertThat(workItems.owned(run.getId(),1L).getStatus()).isEqualTo("NEEDS_RECONCILE");
        assertThatThrownBy(()->workItems.retry(run.getId(),node.getId(),1L)).isInstanceOf(com.pams.common.BizException.class);
        workItems.release(run.getId(),"test-lease");
        reconciliation.reconcile(action.planId(),ctx);
        assertThat(workItems.ready(run.getId())).isPresent();
        assertThat(workItems.sourceFacts(workItems.nodes(run.getId()).getFirst())).contains("planId","SUCCESS");
    }
    @Test void realConfirmationGateRequiresTokenAndRepeatedConfirmationDoesNotWriteAgain() {
        var prepared=tool.execute(ctx,args());var raw=prepared.plan();assertThat(raw).as(prepared.summary()).isNotNull();
        long count=plans.count();
        var pending=gate.createPlan(ctx,tool,raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,raw.preview());
        assertThat(plans.count()).isEqualTo(count);
        assertThatThrownBy(()->gate.confirm(pending.planId(),"invalid",ctx)).hasMessageContaining("令牌");
        var confirmed=gate.confirm(pending.planId(),pending.confirmToken(),ctx);
        assertThat(confirmed.get("status")).as(confirmed.toString()).isEqualTo("SUCCESS");
        assertThat(plans.count()).isEqualTo(count+1);
        gate.confirm(pending.planId(),pending.confirmToken(),ctx);assertThat(plans.count()).isEqualTo(count+1);
    }
    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentCommitOfOneOperationCreatesOnlyOneDraft() throws Exception {
        var prepared=tool.execute(ctx,args());assertThat(prepared.plan()).as(prepared.summary()).isNotNull();
        var action=task(prepared.plan());long before=plans.count();
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<java.util.concurrent.Future<WriteAgentTool.CommitResult>>();
            for(int i=0;i<2;i++) jobs.add(pool.submit(()->{
                OrgContext.set(1L);
                try {start.await();return revisions.commit(ctx.derive(null,null),action);}finally{OrgContext.clear();}
            }));
            start.countDown();
            var a=jobs.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS);var b=jobs.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.detail()).isEqualTo(b.detail());assertThat(plans.count()).isEqualTo(before+1);
        }
    }
    @Test void coverageCannotBeForgedByJumpingToFinalPage() throws Exception {
        var document=new PlanDocumentReader.Document(java.util.stream.IntStream.range(0,8).mapToObj(i->Map.<String,Object>of("text","第"+i+"段")).toList(),List.of());
        String baseline=PlanDocumentReader.hash("coverage");
        var first=snapshots.record(ctx,original.getId(),baseline,source,document,4,8);
        assertThat(first.covered()).isZero();
        var start=snapshots.record(ctx,original.getId(),baseline,source,document,0,4);assertThat(start.covered()).isEqualTo(4);
        var end=snapshots.record(ctx,original.getId(),baseline,source,document,4,8);assertThat(end.covered()).isEqualTo(8);
        assertThatThrownBy(()->snapshots.require(end.id(),ctx.derive(ctx.getConversationId()+1,null),false)).hasMessageContaining("不可见");
        db.update("UPDATE agent_plan_snapshot SET expires_at=? WHERE id=?",java.time.LocalDateTime.now().minusSeconds(1),end.id());
        assertThatThrownBy(()->snapshots.require(end.id(),ctx,false)).hasMessageContaining("过期");
    }
    private record MemoryFile(byte[] bytes) implements org.springframework.web.multipart.MultipartFile {
        public String getName(){return "file";}public String getOriginalFilename(){return "预算验证.docx";}
        public String getContentType(){return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
        public boolean isEmpty(){return false;}public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public void transferTo(File file)throws IOException{Files.write(file.toPath(),bytes);}
    }
}
