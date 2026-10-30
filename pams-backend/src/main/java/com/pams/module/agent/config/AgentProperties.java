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

    /**
     * 模型名。
     *
     * <p><b>⚠️ 不要凭记忆写这个 id</b>：2026-10-12 查官方 {@code /chat/completions} 文档，
     * 取值只有 {@code deepseek-flash} 与 {@code deepseek-v4-pro}；旧的
     * {@code deepseek-chat} / {@code deepseek-reasoner} <b>已不在取值列表里</b>。
     *
     * <p><b>实测更正（同日，真 Key 打真 API）</b>：旧 id {@code deepseek-chat} 目前<b>仍返回 200</b> ——
     * 服务端把它<b>静默别名</b>到 {@code deepseek-flash}（响应里的 {@code model} 字段被改写成 flash）。
     * 因此这里原先写的"用旧 id 会每次 400 → 重试 → 降级 stub 规则模式"<b>不成立</b>，
     * 模型名既不是"规则模式"的病因、也不是它的解药（真因是 Key 没进 JVM 环境变量，见 {@link #apiKey}）。
     * 换掉旧 id 仍然值得：不再依赖一个未公开的别名行为，且日志/计费里的模型名与实际一致
     * （别名期会让"我配的是 chat"这件事失真）。
     *
     * <p>取 {@code deepseek-flash}：更快更省。注意思考模式现在是**请求参数**而不是
     * 独立模型（见 {@link #thinking}），所以"想让它思考"不需要换模型名。
     */
    private String model = "deepseek-flash";

    /** 超时（毫秒） */
    private int timeoutMs = 30000;

    /**
     * 单次响应 token 上限（成本控制，D5）。
     *
     * <p>2048 → 8192 的原因：思考模式下<b>推理与正文共享同一个 max_tokens 预算</b>，
     * 推理长的时候 2048 会被推理吃光，响应以 {@code finish_reason=length} 收尾、
     * 正文被截断——表现为"答到一半没了"，而不是任何异常，排查成本极高。
     * 官方思考模式默认给到 64K，这里按成本取一个中间值。
     */
    private int maxTokens = 8192;

    /**
     * 采样温度。
     *
     * <p>⚠️ 思考模式下官方**不生效**（传了不报错、也不起作用）；保留字段是为了
     * 关闭思考模式（{@link #thinking} = false）时仍然能控温，删掉会让那条路径突然失去采样控制。
     */
    private double temperature = 0.3;

    /**
     * 思考模式开关（官方 2026-10-12 起：思考模式是**参数**，不再是独立模型）。
     *
     * <p>请求体里体现为 {@code thinking:{type:"enabled"|"disabled"}}，官方默认 enabled。
     * 关掉它模型就不再产出 {@code reasoning_content}，过程区的思维链会整段消失
     * （V35 的落库与下发都还在，只是没有内容）——所以默认开着：本系统的"过程区"
     * 就是靠推理原文撑起来的。
     *
     * <p><b>与工具调用耦合的硬约束</b>：只要请求带了 {@code tools}，后续请求就必须完整回传
     * 历史 assistant 消息的 {@code reasoning_content}，否则官方 400。回灌实现在
     * {@code AgentOrchestrator} 的工具循环里（{@code assistantCalling(...)}）。
     *
     * <p><b>已复现（2026-10-12，JVM 直连真 API）</b>：第一轮带 {@code tools} + thinking → 200
     * 且返回推理；第二轮同样带 {@code tools}、回灌该轮 assistant 的 {@code tool_calls}
     * 但<b>不带</b> {@code reasoning_content} → <b>HTTP 400</b>：
     * {@code The `reasoning_content` in the thinking mode must be passed back to the API.}
     * （把 {@code thinking} 关掉则不触发）。工具循环的第二轮正好命中这个组合，
     * 所以回灌是<b>必需</b>，不是"面向未来的保险"。
     *
     * <p>⚠️ 曾有一次探测得出"该约束未强制"的相反结论、并据此把注释与文档改软过：原因是那次
     * 没构造出"带 tools + 历史 assistant 带 tool_calls 且产生过推理"这个组合
     * （单轮、或不带 tools 的请求都不会触发）。**不要据此删掉回灌**。
     */
    private boolean thinking = true;

    /**
     * 推理投入档位：{@code none|low|high|max}，官方默认 {@code high}。
     *
     * <p>官方也接受 {@code minimal}/{@code medium}/{@code xhigh}，但会归一化
     * （minimal→low、medium/xhigh→high）。本系统只写归一化后的值：写原始别名会让人
     * 以为"三个档位各有不同效果"，实际拿到的是同一个档位。
     */
    private String reasoningEffort = "high";

    /** 上下文保留的最近轮数（D5 成本控制） */
    private int contextRounds = 6;

    /** Application budget, independent of the provider's advertised window. Includes output reserve. */
    private int contextWindowTokens = 32768;
    private int contextSafetyTokens = 1024;
    private int contextToolResultTokens = 2048;
    private int contextSummaryTokens = 3072;
    private boolean contextSummaryEnabled = true;
    private int contextSummaryBatchSize = 24;
    private int contextSummaryTimeoutMs = 10000;
    private int contextSummaryMaxTokens = 4096;

    /** Agent 独立线程池大小（D1：不用 @Async 默认池，便于限流与指标采集） */
    private int executorCoreSize = 4;
    private int executorMaxSize = 8;
    private int executorQueueCapacity = 64;
    /** Ready dependency nodes per run; separately bounded to avoid nested pool starvation. */
    private int maxParallelNodes = 2;

    /** 单次编排最多允许的工具调用轮数（防 LLM 死循环） */
    private int maxToolIterations = 5;

    /** 每个 StreamEvent 的补拉窗口上限（断连补齐，D7） */
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
    private java.util.Set<Long> collaborationOrgIds = new java.util.HashSet<>();

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
