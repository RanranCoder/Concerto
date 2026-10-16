package com.pams.module.org.service;

import com.pams.common.BizException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 按来源限制成功创建的组织数，同时限制短时请求次数。
 * 校验失败和事务回滚不占用小时额度，被拒绝的请求也不延长等待窗口。
 */
@Component
public class OrgRegisterRateLimiter {

    private static final int MAX_PER_WINDOW = 5;
    private static final long WINDOW_MILLIS = 60 * 60 * 1000L; // 1 小时
    private static final int MAX_ATTEMPTS_PER_MINUTE = 20;
    private static final long ATTEMPT_WINDOW_MILLIS = 60 * 1000L;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public OrgRegisterRateLimiter() {
        this(Clock.systemUTC());
    }

    OrgRegisterRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 同来源的检查和创建串行执行，避免并发请求穿透成功次数上限。 */
    public <T> T register(String key, Supplier<T> createOrganization) {
        String k = (key == null || key.isBlank()) ? "unknown" : key;
        Window w = windows.computeIfAbsent(k, ignored -> new Window());
        synchronized (w) {
            long now = clock.millis();
            if (now - w.successStartAt >= WINDOW_MILLIS) {
                w.successCount = 0;
            }
            if (w.successCount >= MAX_PER_WINDOW) {
                long minutes = Math.max(1, (WINDOW_MILLIS - (now - w.successStartAt) + 59_999) / 60_000);
                throw new BizException(2015, "当前来源创建组织次数已达上限，请 " + minutes + " 分钟后重试");
            }
            if (now - w.attemptStartAt >= ATTEMPT_WINDOW_MILLIS) {
                w.attemptCount = 0;
            }
            if (w.attemptCount >= MAX_ATTEMPTS_PER_MINUTE) {
                long seconds = Math.max(1, (ATTEMPT_WINDOW_MILLIS - (now - w.attemptStartAt) + 999) / 1000);
                throw new BizException(2015, "注册请求过于频繁，请 " + seconds + " 秒后重试");
            }
            if (w.attemptCount == 0) w.attemptStartAt = now;
            w.attemptCount++;
            // Supplier 调用的是 Spring 事务代理；只有事务成功提交后才增加成功次数。
            T result = createOrganization.get();
            if (w.successCount == 0) w.successStartAt = clock.millis();
            w.successCount++;
            return result;
        }
    }

    private static class Window {
        private long successStartAt;
        private int successCount;
        private long attemptStartAt;
        private int attemptCount;
    }
}
