package com.pams.module.knowledge.service;

import com.pams.common.OrgContext;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.knowledge.repository.*;
import com.pams.module.knowledge.retriever.KnowledgeSearchMode;
import com.pams.module.knowledge.snapshot.SnapshotGenerator;
import com.pams.module.knowledge.snapshot.SnapshotSpecRegistry;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAdminServiceStatsTest {
    private KnowledgeAdminService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        service = new KnowledgeAdminService(mock(KbDocumentRepository.class), mock(KbChunkRepository.class),
                mock(KbRetrievalLogRepository.class), mock(KbSnapshotRepository.class), mock(KnowledgeIndexer.class),
                new KnowledgeSearchMode(), mock(SnapshotGenerator.class), mock(SnapshotSpecRegistry.class));
        jdbc = mock(JdbcTemplate.class);
        PermissionResolver resolver = mock(PermissionResolver.class);
        when(resolver.canAccess(anyString(), anyLong(), any(ToolContext.class))).thenReturn(true);
        ReflectionTestUtils.setField(service, "accessJdbc", jdbc);
        ReflectionTestUtils.setField(service, "accessResolver", resolver);
        OrgContext.set(1L);
        var user = new LoginUser(1L, "reader", "Reader", "TEACHER", 5, null, null, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void cleanup() {
        OrgContext.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void statsAcceptsMysqlAndH2DatetimeValues(boolean mysql) {
        LocalDateTime older = LocalDateTime.of(2026, 10, 17, 12, 0);
        LocalDateTime latest = older.plusDays(1);
        when(jdbc.queryForList(anyString(), eq(1L))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM kb_document")) return List.of();
            if (sql.contains("FROM kb_chunk")) {
                if (sql.contains("indexed_at")) return List.of(
                        chunk(1L, mysql ? older : Timestamp.valueOf(older)),
                        chunk(2L, mysql ? latest : Timestamp.valueOf(latest)), chunk(3L, null));
                return List.of(chunk(1L, null), chunk(2L, null), chunk(3L, null));
            }
            if (sql.contains("FROM kb_snapshot")) {
                if (sql.contains("as_of")) return List.of(
                        snapshot(1L, mysql ? latest : Timestamp.valueOf(latest)), snapshot(2L, null));
                return List.of(snapshot(1L, null), snapshot(2L, null));
            }
            throw new AssertionError("Unexpected SQL: " + sql);
        });

        var stats = service.stats();

        assertThat(stats.get("chunkCount")).isEqualTo(3);
        assertThat(stats.get("snapshotCount")).isEqualTo(2);
        assertThat(stats.get("lastIndexedAt")).isEqualTo(latest);
        assertThat(stats.get("snapshotLatestAsOf")).isEqualTo(latest);
    }

    private Map<String, Object> chunk(long id, Object indexedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("source_type", "manual");
        row.put("source_id", id);
        row.put("indexed_at", indexedAt);
        return row;
    }

    private Map<String, Object> snapshot(long id, Object asOf) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("spec_code", "overview");
        row.put("as_of", asOf);
        return row;
    }
}
