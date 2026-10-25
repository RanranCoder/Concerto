package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.PartyAffairsManagementSystemApplication;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.orchestration.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.stream.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.LoginUser;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Separate test-only JVM. Can only boot against a named isolated database. */
public class S5RuntimeProcess {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pams_s5_[a-zA-Z0-9_]+\\?.*"))throw new IllegalStateException("Refusing a non-sandbox database");
        String phase=args[0];
        var app=SpringApplication.run(PartyAffairsManagementSystemApplication.class,
            "--spring.profiles.active=test","--server.port="+("serve".equals(phase)?"18080":"0"),
            "--pams.agent.thinking=false","--pams.agent.max-tokens=2048","--pams.agent.timeout-ms=60000",
            "--pams.agent.max-tool-iterations=4","--logging.level.root=WARN","--spring.main.banner-mode=off");
        if("serve".equals(phase)) {
            String database=app.getBean(JdbcTemplate.class).queryForObject("select database()",String.class);
            Files.writeString(Path.of(args[1]),new ObjectMapper().writeValueAsString(Map.of("database",database,"pid",ProcessHandle.current().pid(),"port",18080)));
            return;
        }
        try {
            OrgContext.set(1L);var auth=app.getBean(TenantAuthorizationService.class);
            var permissions=auth.authorities(1L).stream().map(SimpleGrantedAuthority::new).toList();
            var user=new LoginUser(1L,"teacher","S5 恢复测试","TEACHER",5,null,null,1L);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,permissions));
            var publisher=app.getBean(AgentEventPublisher.class);var runs=app.getBean(AgentRunService.class);
            var state=app.getBean(WorkItemState.class);var tasks=app.getBean(AgentTaskRepository.class);
            var gate=app.getBean(TaskGate.class);var db=app.getBean(JdbcTemplate.class);var mapper=new ObjectMapper();
            Path checkpoint=Path.of(args[1]);
            if("prepare".equals(phase)) {
                var saved=new ArrayList<Map<String,Object>>();
                for(boolean uncertain:List.of(false,true)) {
                    String name="S5-restart-"+UUID.randomUUID();
                    var ctx=auth.refresh(new ToolContext(user,permissions,null,publisher.newStream("s5-restart",1L)));
                    var run=runs.startRun(null,1L,"S5 进程中断验收","P1");ctx=ctx.withRunId(run.getId());
                    var bb=new Blackboard();var node=new Blackboard.PlanItem();node.setId(1);node.setAgent("generalist");node.setTask("创建测试活动");bb.getPlan().add(node);
                    state.initialize(run.getId(),bb,ctx);String lease=UUID.randomUUID().toString();
                    if(!state.acquire(run.getId(),lease))throw new IllegalStateException("Lease not acquired");
                    var work=state.begin(run.getId(),state.nodes(run.getId()).getFirst().getId(),lease);
                    ctx=ctx.withWorkItem(work.getId(),work.getRevision()).withAttempt(work.getAttempt());
                    var preview=app.getBean(SpecialistAgent.class).execute("generalist","创建活动",ctx,
                        List.of(new com.pams.module.agent.llm.LlmProvider.ToolCallRequest("s5-create","createActivity",Map.of("name",name,"startDate","2026-11-20"))),ctx.getStreamId(),"teacher");
                    if(preview.plan()==null)throw new IllegalStateException("No preview");
                    state.complete(work,preview,1,lease);var task=tasks.findById(preview.plan().planId()).orElseThrow();
                    if(uncertain) {
                        if(tasks.claimOwned(task.getId(),AgentTask.PENDING_CONFIRM,AgentTask.RUNNING,LocalDateTime.now(),RuntimeIdentity.ID)!=1)throw new IllegalStateException("No commit claim");
                        // Inject process death AFTER real business commit, BEFORE the Agent receipt is persisted.
                        var receipt=((WriteAgentTool)app.getBean(ToolRegistry.class).find("createActivity").orElseThrow()).commit(ctx,gate.readPlan(task));
                        if(!"SUCCESS".equals(receipt.status()))throw new IllegalStateException("Business commit failed");
                    } else state.release(run.getId(),lease);
                    saved.add(Map.of("runId",run.getId(),"taskId",task.getId(),"uncertain",uncertain,"activityName",name,"tokenSha256",hash(task.getConfirmToken())));
                }
                Path temporary=checkpoint.resolveSibling(checkpoint.getFileName()+".tmp");
                Files.writeString(temporary,mapper.writeValueAsString(saved));
                Files.move(temporary,checkpoint,StandardCopyOption.ATOMIC_MOVE);
                new java.util.concurrent.CountDownLatch(1).await(); // Parent forcibly terminates this JVM.
            } else if("verify".equals(phase)) {
                var rows=mapper.readTree(Files.readString(checkpoint));
                if(rows==null || !rows.isArray() || rows.size()!=2)throw new IllegalStateException("Missing recovery checkpoint cases");
                var outbox=app.getBean(AgentRunOutbox.class);
                // Recovery uses the changed JVM identity and persisted action state.
                for(int i=0;i<10;i++)outbox.reconcile();
                var evidence=new ArrayList<Map<String,Object>>();
                for(var row:rows) {
                    long runId=row.path("runId").asLong(),taskId=row.path("taskId").asLong();boolean uncertain=row.path("uncertain").asBoolean();
                    var task=tasks.findById(taskId).orElseThrow();String name=row.path("activityName").asText();
                    if(!hash(task.getConfirmToken()).equals(row.path("tokenSha256").asText()))throw new IllegalStateException("Persisted token changed");
                    var ctx=auth.refresh(new ToolContext(user,permissions,null,publisher.newStream("s5-verify",1L)));
                    long before=db.queryForObject("select count(*) from activity where name=?",Long.class,name);
                    if(uncertain) {
                        if(before!=1 || !"NEEDS_RECONCILE".equals(state.nodes(runId).getFirst().getStatus()))throw new IllegalStateException("Uncertain business result was not isolated");
                    } else if(before!=0)throw new IllegalStateException("Preview wrote before confirmation");
                    gate.confirm(taskId,task.getConfirmToken(),ctx);gate.confirm(taskId,task.getConfirmToken(),ctx);
                    long after=db.queryForObject("select count(*) from activity where name=?",Long.class,name);
                    if(after!=1)throw new IllegalStateException("Duplicate or missing business effect");
                    evidence.add(Map.of("runId",runId,"uncertain",uncertain,"rowsBefore",before,"rowsAfter",after,"recoveryStatus",state.nodes(runId).getFirst().getStatus()));
                }
                Files.writeString(checkpoint.resolveSibling("verified.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("status","PASS","actualJvmKilled",true,"cases",evidence)));
            } else throw new IllegalArgumentException(phase);
        } finally {SecurityContextHolder.clearContext();OrgContext.clear();app.close();}
    }
    private static String hash(String text) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
}
