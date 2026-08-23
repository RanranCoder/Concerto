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
        // M8: 已结束/已归档状态禁止回退一步，防止绕过「已结束不可编辑」保护；
        //     FINISHED/ARCHIVED 仅允许前进（FINISHED→ARCHIVED 归档），其余状态仍可前进/回退一步。
        if (this == FINISHED || this == ARCHIVED) {
            return target == next();
        }
        return target == next() || target == prev();
    }
}
