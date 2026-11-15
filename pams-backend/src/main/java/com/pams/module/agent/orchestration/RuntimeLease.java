package com.pams.module.agent.orchestration;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class RuntimeLease {
    private static final Logger log = LoggerFactory.getLogger(RuntimeLease.class);
    private final JdbcTemplate db;
    private final java.util.concurrent.ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("agent-runtime-lease").factory());

    public RuntimeLease(JdbcTemplate db) {
        this.db = db;
    }

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    void start() {
        renew();
        scheduler.scheduleWithFixedDelay(this::renew, 5, 5, TimeUnit.SECONDS);
    }

    private void renew() {
        try {
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
            var leases = db.queryForList("""
                    SELECT CASE WHEN expires_at <= CURRENT_TIMESTAMP THEN 1 ELSE 0 END
                    FROM agent_runtime_lease WHERE owner_id = ?
                    """, Integer.class, owner);
            if (!leases.isEmpty()) return leases.getFirst() == 1;
        }
        // Rolling upgrades may still have work claimed by an older version without heartbeats.
        return claimedAt != null && !claimedAt.isAfter(LocalDateTime.now().minusMinutes(30));
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
