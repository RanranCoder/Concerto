package com.pams.module.agent.orchestration;

import com.pams.config.SqlDialect;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class RuntimeLease {
    private static final Logger log = LoggerFactory.getLogger(RuntimeLease.class);
    private final JdbcTemplate db;
    private final Environment environment;
    private final java.util.concurrent.ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("agent-runtime-lease").factory());

    public RuntimeLease(JdbcTemplate db, Environment environment) {
        this.db = db;
        this.environment = environment;
    }

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (environment.matchesProfiles("postgresql-candidate")) return;
        renew();
        scheduler.scheduleWithFixedDelay(this::renew, 5, 5, TimeUnit.SECONDS);
    }

    private void renew() {
        try {
            if (SqlDialect.postgresql(db)) {
                db.update("""
                        INSERT INTO agent_runtime_lease(owner_id, expires_at)
                        VALUES (?, clock_timestamp() + INTERVAL '30 seconds')
                        ON CONFLICT (owner_id) DO UPDATE SET expires_at = clock_timestamp() + INTERVAL '30 seconds'
                        """, RuntimeIdentity.ID);
                db.update("DELETE FROM agent_runtime_lease WHERE expires_at < clock_timestamp() - INTERVAL '1 day'");
                return;
            }
            db.update("""
                    INSERT INTO agent_runtime_lease(owner_id, expires_at)
                    VALUES (?, TIMESTAMPADD(SECOND, 30, CURRENT_TIMESTAMP))
                    ON DUPLICATE KEY UPDATE expires_at = TIMESTAMPADD(SECOND, 30, CURRENT_TIMESTAMP)
                    """, RuntimeIdentity.ID);
            db.update("DELETE FROM agent_runtime_lease WHERE expires_at < TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP)");
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("灵思运行实例租约续期失败", e);
        }
    }

    public boolean expired(String owner, LocalDateTime claimedAt) {
        if (owner != null) {
            String now = SqlDialect.postgresql(db) ? "clock_timestamp()" : "CURRENT_TIMESTAMP";
            var leases = db.queryForList("SELECT expires_at <= " + now
                    + " FROM agent_runtime_lease WHERE owner_id = ?", Boolean.class, owner);
            if (!leases.isEmpty()) return leases.getFirst();
        }
        // Rolling upgrades may still have work claimed by an older version without heartbeats.
        return claimedAt != null && !claimedAt.isAfter(LocalDateTime.now().minusMinutes(30));
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
