package com.pams.module.activity.entity;

public enum ActivityStatus {
    ASSIGNED, PLANNING, PLAN_REVIEW, EXECUTING, FINISHED, ARCHIVED;

    public ActivityStatus next() {
        ActivityStatus[] v = values();
        return ordinal() + 1 < v.length ? v[ordinal() + 1] : this;
    }
    public ActivityStatus prev() {
        return ordinal() > 0 ? values()[ordinal() - 1] : this;
    }
    public boolean canGoTo(ActivityStatus target) {
        return target == next() || target == prev();
    }
}
