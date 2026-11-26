package com.pams.security;

import org.springframework.stereotype.Component;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pams.common.BizException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 登录失败限流：同一用户名连续失败 N 次后锁定 T 分钟，防止无限速暴力破解。
 * 内存实现（单机部署足够；重启即清空，符合本项目内网规模）。
 */
@Component
public class LoginRateLimiter {
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 5 * 60 * 1000L; // 5 分钟

    private final Cache<String, FailureWindow> failures = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMillis(LOCK_MILLIS)).build();
    private final Object[] locks = new Object[256];

    public LoginRateLimiter() {
        Arrays.setAll(locks, ignored -> new Object());
    }

    public <T> T authenticate(String username, Supplier<Optional<T>> verify) {
        String key = username.toLowerCase(Locale.ROOT);
        synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
            FailureWindow window = failures.getIfPresent(key);
            if (window != null && window.count >= MAX_FAILURES) {
                throw new BizException(1003, "账号已被临时锁定，请 " + (LOCK_MILLIS / 60_000) + " 分钟后重试");
            }
            Optional<T> verified = verify.get();
            if (verified.isPresent()) {
                failures.invalidate(key);
                return verified.get();
            }
            if (window == null) window = failures.get(key, ignored -> new FailureWindow());
            window.count++;
            throw new BizException(1001, "用户名或密码错误");
        }
    }

    private static class FailureWindow {
        private int count;
    }
}
