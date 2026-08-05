package com.pams.module.notification.event;

public class TaskAssignedEvent {
    private final Long taskId;
    private final Long activityId;
    private final Long deptId;
    private final String taskName;
    private final Long senderId;

    public TaskAssignedEvent(Long taskId, Long activityId, Long deptId, String taskName, Long senderId) {
        this.taskId = taskId;
        this.activityId = activityId;
        this.deptId = deptId;
        this.taskName = taskName;
        this.senderId = senderId;
    }

    public Long getTaskId() { return taskId; }
    public Long getActivityId() { return activityId; }
    public Long getDeptId() { return deptId; }
    public String getTaskName() { return taskName; }
    public Long getSenderId() { return senderId; }
}
