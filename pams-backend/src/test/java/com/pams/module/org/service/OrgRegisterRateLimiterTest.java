package com.pams.module.org.service;

import com.pams.common.BizException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrgRegisterRateLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final OrgRegisterRateLimiter limiter = new OrgRegisterRateLimiter(clock);

    @Test
    void failedValidationDoesNotConsumeSuccessfulRegistrationQuota() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> limiter.register("same-ip", () -> {
                throw new BizException(2004, "管理员用户名已存在");
            })).isInstanceOf(BizException.class).hasMessage("管理员用户名已存在");
        }
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.register("same-ip", () -> "created")).isEqualTo("created");
        }
        assertThatThrownBy(() -> limiter.register("same-ip", () -> "unexpected"))
                .hasMessageContaining("60 分钟");
    }

    @Test
    void repeatedFailuresAreLimitedForOneMinuteAndRejectedRequestsDoNotExtendTheWindow() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> limiter.register("ip", () -> {
                throw new IllegalArgumentException("invalid");
            })).hasMessage("invalid");
        }
        clock.advanceSeconds(10);
        assertThatThrownBy(() -> limiter.register("ip", () -> "unexpected"))
                .hasMessageContaining("50 秒");
        clock.advanceSeconds(50);
        assertThat(limiter.register("ip", () -> "created")).isEqualTo("created");
    }

    @Test
    void hourlyQuotaExpiresExactlyAtTheBoundaryAndDoesNotAffectOtherSources() {
        for (int i = 0; i < 5; i++) limiter.register("ip", () -> true);
        assertThat(limiter.register("another-ip", () -> true)).isTrue();
        clock.advanceSeconds(3599);
        assertThatThrownBy(() -> limiter.register("ip", () -> true)).hasMessageContaining("1 分钟");
        clock.advanceSeconds(1);
        assertThat(limiter.register("ip", () -> true)).isTrue();
    }

    @Test
    void concurrentRequestsCannotCreateMoreThanFiveOrganizations() throws Exception {
        AtomicInteger created = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 12; i++) {
                executor.submit(() -> {
                    try { limiter.register("ip", created::incrementAndGet); }
                    catch (BizException ignored) { /* 超出额度的请求不执行创建 */ }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(created.get()).isEqualTo(5);
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-16T00:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
