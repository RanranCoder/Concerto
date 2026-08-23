package com.pams.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败限流：同一用户名连续失败 N 次后锁定 T 分钟，防止无限速暴力破解。
 * 内存实现（单机部署足够；重启即清空，符合本项目内网规模）。
 */
@Component
public class LoginRateLimiter {
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 5 * 60 * 1000L; // 5 分钟

    private final Map<String, FailureWindow> failures = new ConcurrentHashMap<>();

    public void checkLocked(String username) {
        if (username == null) return;
        FailureWindow w = failures.get(username);
        if (w != null && w.isLocked()) {
            throw new com.pams.common.BizException(1003,
                "账号已被临时锁定，请 " + (LOCK_MILLIS / 60000) + " 分钟后重试");
        }
    }

    public void recordFailure(String username) {
        if (username == null) return;
        failures.compute(username, (k, w) -> {
            if (w == null || w.isExpired()) return new FailureWindow();
            w.increment();
            return w;
        });
    }

    public void clear(String username) {
        if (username != null) failures.remove(username);
    }

    private static class FailureWindow {
        private final AtomicInteger count = new AtomicInteger(1);
        private final long firstFailAt = System.currentTimeMillis();

        boolean isExpired() {
            return System.currentTimeMillis() - firstFailAt > LOCK_MILLIS;
        }

        boolean isLocked() {
            return count.get() >= MAX_FAILURES && !isExpired();
        }

        void increment() {
            count.incrementAndGet();
        }
    }
}
