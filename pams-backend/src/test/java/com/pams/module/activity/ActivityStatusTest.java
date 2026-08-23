package com.pams.module.activity;

import com.pams.module.activity.entity.ActivityStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 活动状态机 canGoTo 规则测试。
 * 枚举步进：ASSIGNED -> PLANNING -> PLAN_REVIEW -> EXECUTING -> FINISHED -> ARCHIVED。
 * M8：FINISHED / ARCHIVED 为已结束状态，禁止回退一步（防止绕过「已结束不可编辑」保护）。
 */
class ActivityStatusTest {

    @Test
    void finished_cannotRollbackToExecuting() {
        assertThat(ActivityStatus.FINISHED.canGoTo(ActivityStatus.EXECUTING)).isFalse();
    }

    @Test
    void archived_cannotRollbackToFinished() {
        assertThat(ActivityStatus.ARCHIVED.canGoTo(ActivityStatus.FINISHED)).isFalse();
    }

    @Test
    void finished_canForwardToArchived() {
        assertThat(ActivityStatus.FINISHED.canGoTo(ActivityStatus.ARCHIVED)).isTrue();
    }

    @Test
    void executing_canGoToFinished() {
        assertThat(ActivityStatus.EXECUTING.canGoTo(ActivityStatus.FINISHED)).isTrue();
    }

    @Test
    void planReview_canGoToExecuting() {
        assertThat(ActivityStatus.PLAN_REVIEW.canGoTo(ActivityStatus.EXECUTING)).isTrue();
    }

    @Test
    void unfinished_canRollbackOneStep() {
        // 未结束状态仍允许回退一步（回归保护）
        assertThat(ActivityStatus.EXECUTING.canGoTo(ActivityStatus.PLAN_REVIEW)).isTrue();
        assertThat(ActivityStatus.PLANNING.canGoTo(ActivityStatus.ASSIGNED)).isTrue();
    }

    @Test
    void canAdvanceOneStep() {
        assertThat(ActivityStatus.ASSIGNED.canGoTo(ActivityStatus.PLANNING)).isTrue();
    }

    @Test
    void skipStep_isNotAllowed() {
        // 跳级不被允许：PLANNING 只能到 PLAN_REVIEW，不能直达 EXECUTING
        assertThat(ActivityStatus.PLANNING.canGoTo(ActivityStatus.EXECUTING)).isFalse();
        assertThat(ActivityStatus.EXECUTING.canGoTo(ActivityStatus.ARCHIVED)).isFalse();
    }

    @Test
    void sameState_isNotAllowed() {
        assertThat(ActivityStatus.EXECUTING.canGoTo(ActivityStatus.EXECUTING)).isFalse();
    }
}
