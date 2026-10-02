package com.pams.module.org.service;

import com.pams.common.BizException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组织注册限流：按来源（IP）限制单位时间内的注册次数，防止刷注册。
 * 内存实现（单机部署足够，与 {@code LoginRateLimiter} 同款口径）。
 */
@Component
public class OrgRegisterRateLimiter {

    private static final int MAX_PER_WINDOW = 5;
    private static final long WINDOW_MILLIS = 60 * 60 * 1000L; // 1 小时

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** 超限抛 BizException。 */
    public void checkAllowed(String key) {
        String k = (key == null || key.isBlank()) ? "unknown" : key;
        Window w = windows.compute(k, (ignored, cur) -> {
            long now = System.currentTimeMillis();
            if (cur == null || now - cur.startAt > WINDOW_MILLIS) {
                return new Window(now);
            }
            cur.count++;
            return cur;
        });
        if (w.count > MAX_PER_WINDOW) {
            throw new BizException(2015, "注册过于频繁，请稍后再试");
        }
    }

    private static class Window {
        private final long startAt;
        private int count = 1;

        Window(long startAt) {
            this.startAt = startAt;
        }
    }
}
