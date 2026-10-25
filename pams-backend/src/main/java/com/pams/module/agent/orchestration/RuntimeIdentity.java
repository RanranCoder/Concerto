package com.pams.module.agent.orchestration;
import java.time.LocalDateTime;
import java.util.UUID;
/** Current single-instance broker deployment: a different JVM cannot still own this process's work. */
public final class RuntimeIdentity {
    public static final String ID=UUID.randomUUID().toString();
    public static final LocalDateTime STARTED=LocalDateTime.now();
    private RuntimeIdentity() {}
}
