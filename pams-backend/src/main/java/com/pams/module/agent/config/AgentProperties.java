package com.pams.module.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 灵思配置（业务设计方案 D5 配置项）。
 *
 * <p>密钥治理与现有"密钥走环境变量"口径一致：{@code PAMS_AGENT_API_KEY}。
 * 本类的 {@code apiKey} 字段<b>不写默认值</b>，避免密钥进入版本库。
 */
@Data
@Component
@ConfigurationProperties(prefix = "pams.agent")
public class AgentProperties {

    /** 主 provider：deepseek / stub */
    private String provider = "deepseek";

    /** OpenAI 兼容端点 */
    private String baseUrl = "https://api.deepseek.com";

    /** 模型名 */
    private String model = "deepseek-chat";

    /** 超时（毫秒） */
    private int timeoutMs = 30000;

    /** 单次响应 token 上限（成本控制，D5） */
    private int maxTokens = 2048;

    /** 采样温度 */
    private double temperature = 0.3;

    /** 上下文保留的最近轮数（D5 成本控制） */
    private int contextRounds = 6;

    /** Agent 独立线程池大小（D1：不用 @Async 默认池，便于限流与指标采集） */
    private int executorCoreSize = 4;
    private int executorMaxSize = 8;
    private int executorQueueCapacity = 64;

    /** 单次编排最多允许的工具调用轮数（防 LLM 死循环） */
    private int maxToolIterations = 5;

    /** 每个 StreamEvent 的补拉窗口上限（断连补齐，D7） */
    private int streamBufferSize = 500;

    /**
     * 解析 API Key：优先环境变量 {@code PAMS_AGENT_API_KEY}，其次 {@code pams.agent.api-key}
     * （后者仅用于本地临时调试，不入版本库）。
     */
    private String apiKey = "";

    public String resolveApiKey() {
        String env = System.getenv("PAMS_AGENT_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        return apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
    }
}
