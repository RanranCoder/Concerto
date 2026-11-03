package com.pams.module.agent.service;

import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="PAMS_S5_BATCH6",matches="true")
class BatchSixMySqlAcceptanceTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){com.pams.module.agent.testing.BatchSixDatabase.configure(registry);}
    @Autowired JdbcTemplate db;@Autowired ArticleRepository articles;@Autowired AgentEditorialService editorial;
    @Autowired AgentTaskRepository tasks;@Autowired TaskGate gate;@Autowired ToolRegistry tools;
    @Autowired ActionReconciliationService reconciliation;@Autowired PlatformTransactionManager transactions;
    static final List<Map<String,Object>> evidence=new CopyOnWriteArrayList<>();
    ToolContext ctx;
    @BeforeEach void setup(){OrgContext.set(1L);ctx=new ToolContext(new LoginUser(1L,"teacher","隔离并发验收","TEACHER",5,null,null,1L),List.of("content:view","article:edit","article:submit").stream().map(SimpleGrantedAuthority::new).toList(),null,null);}
    @AfterEach void clear(){OrgContext.clear();}
    Article article(){var a=new Article();a.setTitle("B6-MySQL-"+UUID.randomUUID());a.setContent("预算2500元，其余保留。");a.setAuthorId(1L);a.setArticleType(Article.ArticleType.REPORT);a.setStatus(Article.ArticleStatus.DRAFT);a.setDeleted(0);return articles.saveAndFlush(a);}
    AgentActionPlan preview(Article a){var read=editorial.readArticle(ctx,Map.of("articleId",a.getId()),false);var raw=editorial.prepare("prepareArticleRevision",ctx,Map.of("articleId",a.getId(),"baseline",read.payload().get("baseline"),"changes",Map.of("content",Map.of("before","2500元","after","3000元")))).plan();var tool=tools.find("prepareArticleRevision").orElseThrow();return gate.createPlan(ctx,tool,raw.summary(),raw.total(),raw.items(),raw.skipped(),raw.detailLines(),false,raw.preview());}
    AgentActionPlan running(AgentActionPlan p){assertThat(tasks.claimOwned(p.planId(),"PENDING_CONFIRM","RUNNING",java.time.LocalDateTime.now(),"batch6-test")).isEqualTo(1);return p;}
    <T> T scoped(Callable<T> work)throws Exception{OrgContext.set(1L);try{return work.call();}finally{OrgContext.clear();}}
    @Test void v64AndSchemaScopedAccountAreRealMySql(){
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).matches("pams_s5_batch6_[a-f0-9]{12}");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='64' AND success=1",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('article','schedule') AND column_name='revision' AND is_nullable='NO'",Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_work_item' AND column_name IN ('contract_json','result_json') AND data_type='json'",Long.class)).isEqualTo(2);
        var grants=db.queryForList("SHOW GRANTS FOR CURRENT_USER",String.class);assertThat(grants).allSatisfy(g->assertThat(g).satisfiesAnyOf(v->assertThat(v).contains("GRANT USAGE ON *.*"),v->assertThat(v.replace("\\","")).contains("pams_s5_batch6_")));
        evidence.add(Map.of("case","V64_MYSQL_MIGRATION","pass",true,"database",db.queryForObject("SELECT DATABASE()",String.class),"serverVersion",db.queryForObject("SELECT VERSION()",String.class)));
    }
    @Test void concurrentSameOperationHasOneRevisionAndOneReceipt()throws Exception{
        var a=article();var p=running(preview(a));var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->scoped(()->{start.await();return editorial.commit("prepareArticleRevision",ctx,p);}));
            var second=pool.submit(()->scoped(()->{start.await();return editorial.commit("prepareArticleRevision",ctx,p);}));start.countDown();
            assertThat(first.get(30,TimeUnit.SECONDS).detail()).isEqualTo(second.get(30,TimeUnit.SECONDS).detail());
        }
        assertThat(articles.findById(a.getId()).orElseThrow().getRevision()).isEqualTo(1L);
        assertThat(articles.findById(a.getId()).orElseThrow().getContent()).contains("3000元");evidence.add(Map.of("case","CONCURRENT_OPERATION_IDEMPOTENCY","pass",true,"articleId",a.getId(),"revision",1));
    }
    @Test void concurrentGateConfirmationDoesNotCommitTwice()throws Exception{
        var a=article();var p=preview(a);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->scoped(()->{start.await();return gate.confirm(p.planId(),p.confirmToken(),ctx);}));var second=pool.submit(()->scoped(()->{start.await();return gate.confirm(p.planId(),p.confirmToken(),ctx);}));start.countDown();
            first.get(30,TimeUnit.SECONDS);second.get(30,TimeUnit.SECONDS);
        }
        assertThat(tasks.findById(p.planId()).orElseThrow().getStatus()).isEqualTo("SUCCESS");assertThat(articles.findById(a.getId()).orElseThrow().getRevision()).isEqualTo(1L);evidence.add(Map.of("case","CONCURRENT_GATE_CONFIRM","pass",true,"articleId",a.getId(),"revision",1));
    }
    @Test void rollbackRemovesBusinessChangeAndReceiptAndNeverReplays(){
        var a=article();var p=running(preview(a));new TransactionTemplate(transactions).execute(status->{editorial.commit("prepareArticleRevision",ctx,p);status.setRollbackOnly();return null;});
        assertThat(articles.findById(a.getId()).orElseThrow().getRevision()).isZero();assertThat(articles.findById(a.getId()).orElseThrow().getContent()).contains("2500元");
        tasks.finish(p.planId(),"RESULT_UNKNOWN",null,java.time.LocalDateTime.now());
        try{assertThat(reconciliation.reconcile(p.planId(),ctx)).containsEntry("reconciled",false);}catch(Exception e){throw new AssertionError(e);}
        assertThat(articles.findById(a.getId()).orElseThrow().getRevision()).isZero();evidence.add(Map.of("case","ROLLBACK_AND_NO_REPLAY","pass",true,"articleId",a.getId(),"revision",0));
    }
    @Test void overlappingNativeEditCannotOverwriteCommittedAgentRevision()throws Exception{
        var a=article();var p=running(preview(a));var loaded=new CountDownLatch(1);var committed=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var nativeEdit=pool.submit(()->scoped(()->new TransactionTemplate(transactions).execute(status->{var stale=articles.findById(a.getId()).orElseThrow();loaded.countDown();try{if(!committed.await(20,TimeUnit.SECONDS))throw new IllegalStateException("agent commit timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}stale.setTitle("不能覆盖的旧版本");articles.saveAndFlush(stale);return null;})));
            assertThat(loaded.await(20,TimeUnit.SECONDS)).isTrue();try{editorial.commit("prepareArticleRevision",ctx,p);}finally{committed.countDown();}
            assertThatThrownBy(()->nativeEdit.get(30,TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        }
        assertThat(articles.findById(a.getId()).orElseThrow().getTitle()).isEqualTo(a.getTitle());assertThat(articles.findById(a.getId()).orElseThrow().getContent()).contains("3000元");evidence.add(Map.of("case","OVERLAPPING_NATIVE_OPTIMISTIC_LOCK","pass",true,"articleId",a.getId()));
    }
    @AfterAll static void save()throws Exception{var output=java.nio.file.Path.of(System.getProperty("pams.batch6.output","../docs/audits/agent-acceptance-20261102"));java.nio.file.Files.createDirectories(output);AgentPreparedOperations.mapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("mysql-business-evidence.json").toFile(),evidence);}
}
