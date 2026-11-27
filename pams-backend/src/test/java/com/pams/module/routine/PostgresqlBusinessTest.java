package com.pams.module.routine;

import com.pams.common.OrgContext;
import com.pams.module.agent.orchestration.WorkItemState;
import com.pams.module.agent.orchestration.AgentRunService;
import com.pams.module.knowledge.retriever.ChunkSearchRepository;
import com.pams.module.knowledge.repository.KbDocumentRepository;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.service.NotificationOutboxWorker;
import com.pams.module.notification.service.NotificationPipeline;
import com.pams.support.PostgresqlAcceptanceDatabase;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAMS_POSTGRESQL_ACCEPTANCE", matches = "1")
@Timeout(30)
class PostgresqlBusinessTest {
    @Autowired JdbcTemplate db;
    @Autowired NotificationPipeline notifications;
    @Autowired NotificationOutboxWorker worker;
    @Autowired AgentRunService runs;
    @Autowired WorkItemState work;
    @Autowired ChunkSearchRepository search;
    @Autowired KbDocumentRepository documents;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresqlAcceptanceDatabase.register(registry);
    }

    @Test
    void simultaneousNotificationReplaysCreateOneFactAndOneInboxRow() throws Exception {
        long user = teacher();
        String operation = UUID.randomUUID().toString();
        var ids = concurrently(() -> inOrg(() -> notifications.recordFrozen(NotificationType.SYSTEM,
                        "并发通知", "同一操作冻结受众", user, List.of(user), operation).getId()),
                () -> inOrg(() -> notifications.recordFrozen(NotificationType.SYSTEM,
                        "并发通知", "同一操作冻结受众", user, List.of(user), operation).getId()));
        assertThat(ids.getFirst()).isEqualTo(ids.getLast());
        long message = ids.getFirst();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE event_key=?", Integer.class,
                "agent:" + operation)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE message_id=?",
                Integer.class, message)).isEqualTo(1);
        worker.tick();
        worker.tick();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id=? AND user_id=?",
                Integer.class, message, user)).isEqualTo(1);
    }

    @Test
    void oneAttemptWinsTheAgentRunLease() throws Exception {
        long user = teacher();
        long run = inOrg(() -> runs.startRun(null, user, "并发租约验证", "P1").getId());
        var results = concurrently(
                () -> inOrg(() -> work.acquire(run, UUID.randomUUID().toString())),
                () -> inOrg(() -> work.acquire(run, UUID.randomUUID().toString())));
        assertThat(results).containsExactlyInAnyOrder(true, false);
    }

    @Test
    @Transactional
    void hanQueriesReadOnlyMatchingVisibleContentInTheCurrentOrganization() throws Exception {
        String key = UUID.randomUUID().toString();
        chunk(key + "good", 1L, "部门排班", "组织排班规则", "T1", null);
        chunk(key + "restricted", 1L, "部门排班", "组织排班规则", "T1", "kb:restricted");
        chunk(key + "other-org", 99L, "部门排班", "组织排班规则", "T1", null);
        chunk(key + "metadata", 1L, "成员目录", "组织排班规则", "T2", null);
        for (String query : List.of("班", "排班", "组织排班")) {
            var result = inOrg(() -> search.fulltextT1(query, null, 50));
            assertThat(result.stream().map(c -> c.sourceKey()).filter(s -> s.startsWith(key)))
                    .containsExactly(key + "good");
        }
        assertThat(inOrg(() -> search.titleMatchT2("排班", null, 50)).stream()
                .map(c -> c.sourceKey()).filter(s -> s.startsWith(key))).isEmpty();
    }

    @Test
    @Transactional
    void readsPostgresqlTextAsKnowledgeDocumentBody() throws Exception {
        String body = "<p>活动排班和签到规则</p>";
        long id = db.queryForObject("INSERT INTO kb_document(title,body,org_id) VALUES(?,?,1) RETURNING id",
                Long.class, "中文资料", body);
        assertThat(inOrg(() -> documents.findById(id).orElseThrow().getBody())).isEqualTo(body);
    }

    private void chunk(String key, long org, String title, String content, String tier, String permission) {
        db.update("""
                INSERT INTO kb_chunk(source_type,source_id,source_key,seq,org_id,title,content,tier,required_permission)
                VALUES ('manual',1,?,0,?,?,?,?,?)
                """, key, org, title, content, tier, permission);
    }

    private long teacher() {
        return db.queryForObject("SELECT id FROM sys_user WHERE org_id=1 AND username='teacher'", Long.class);
    }

    private static <T> T inOrg(Callable<T> task) throws Exception {
        OrgContext.set(1L);
        try {
            return task.call();
        } finally {
            OrgContext.clear();
        }
    }

    private static <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(12, TimeUnit.SECONDS), b.get(12, TimeUnit.SECONDS));
        }
    }
}
