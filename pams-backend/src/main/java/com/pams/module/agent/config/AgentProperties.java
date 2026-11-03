package com.pams.module.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "pams.agent")
public class AgentProperties {
    private String provider = "openai";
    private String protocol = "openai_chat_completions";
    private boolean summaryEnabled = false;
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "";
    private int timeoutMs = 30000;
    private int maxTokens = 8192;
    private double temperature = 0.3;
    private int contextRounds = 6;
    private int contextWindowTokens = 32768;
    private int contextSafetyTokens = 1024;
    private int contextToolResultTokens = 2048;
    private int contextSummaryTokens = 3072;
    private boolean contextSummaryEnabled = true;
    private int contextSummaryBatchSize = 24;
    private int contextSummaryTimeoutMs = 10000;
    private int contextSummaryMaxTokens = 4096;
    private int executorCoreSize = 4;
    private int executorMaxSize = 8;
    private int executorQueueCapacity = 64;
    private int maxParallelNodes = 2;
    private int maxToolIterations = 5;
    private int streamBufferSize = 500;
    private int streamCacheMaxEntries = 200;
    private long streamCacheTtlMs = 600000;
    private long streamActiveTtlMs = 3600000;
    private boolean runtimeRecoveryEnabled = true;
    private boolean benchmarkEnabled = false;
    private java.util.Set<Long> benchmarkOrgIds = new java.util.HashSet<>();
    private int benchmarkMaxCalls = 12;
    private int benchmarkMaxTokens = 2048;
    private boolean collaborationEnabled = true;
    /** Experimental plan preview only; never changes the production execution planner. */
    private boolean controlledPlanningEnabled = false;
    /** Independent rollout gate; requires real-model acceptance before production activation. */
    private boolean controlledExecutionEnabled = false;
    private java.util.Set<Long> controlledPlanningOrgIds = new java.util.HashSet<>();
    private java.util.Set<Long> collaborationOrgIds = new java.util.HashSet<>();
    @lombok.ToString.Exclude
    private String apiKey = "";
    public String resolveApiKey() {
        String value = System.getenv("PAMS_AGENT_API_KEY");
        if (value != null && !value.isBlank()) return value.trim();
        return apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
    }
}
