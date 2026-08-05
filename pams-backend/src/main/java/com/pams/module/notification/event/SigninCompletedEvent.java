package com.pams.module.notification.event;

public class SigninCompletedEvent {
    private final Long activityId;
    private final long signed;
    private final long expected;

    public SigninCompletedEvent(Long activityId, long signed, long expected) {
        this.activityId = activityId;
        this.signed = signed;
        this.expected = expected;
    }

    public Long getActivityId() { return activityId; }
    public long getSigned() { return signed; }
    public long getExpected() { return expected; }
}
