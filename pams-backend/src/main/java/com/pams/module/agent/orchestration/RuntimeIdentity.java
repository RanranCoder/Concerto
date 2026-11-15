package com.pams.module.agent.orchestration;
import java.time.LocalDateTime;
import java.util.UUID;
/** Identifies claims made by this JVM. Liveness is tracked separately by RuntimeLease. */
public final class RuntimeIdentity {
    public static final String ID=UUID.randomUUID().toString();
    public static final LocalDateTime STARTED = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()),
            java.time.ZoneId.systemDefault());
    private RuntimeIdentity() {}
}
