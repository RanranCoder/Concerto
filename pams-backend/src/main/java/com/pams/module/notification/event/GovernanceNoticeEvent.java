package com.pams.module.notification.event;

public record GovernanceNoticeEvent(long orgId, long auditId, long actorId, String action, long targetId) {}
