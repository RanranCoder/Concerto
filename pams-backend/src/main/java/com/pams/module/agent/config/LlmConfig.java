package com.pams.module.agent.config;

import com.pams.module.agent.llm.DeepSeekProvider;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.llm.StubProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 网关装配 + <b>按数据分级路由</b>（业务设计方案 D5）。
 *
 * <p>核心建议（原文）：<b>不要把"选一个供应商"当成互斥题</b>——
 * <pre>
 * normal   数据 → DeepSeek API（体验优先，便宜、能力强）
 * sensitive 数据 → 本地 Ollama（若已部署）或"仅摘要出网"（脱敏后再交给云端）
 * restricted 数据 → 根本不出网（PolicyGuard 拦截）
 * </pre>
 *
 * <p>分期落地：一期只接 DeepSeek + Stub；{@code sensitive} 走"仅摘要出网"
 * （工具结果在服务端摘要/脱敏后再进 LLM 上下文），与 D4 既定设计一致，<b>不需额外开发</b>。
 * Ollama 列二期可选，<b>不作为一期阻塞</b>。
 *
 * <p>本类保留一个 {@code providerFor(Sensitivity)} 形状的分派钩子——实现成本约 20 行，
 * 却把"敏感模块怎么做"从设计难题降为一个映射函数。
 */
@Slf4j
@Configuration
public class LlmConfig {

    @Bean
    public StubProvider stubProvider() {
        return new StubProvider();
    }

    @Bean
    public LlmProvider primaryProvider(AgentProperties props, StubProvider stub) {
        String name = props.getProvider() == null ? "deepseek" : props.getProvider().trim().toLowerCase();
        if ("stub".equals(name)) {
            log.info("灵思 LLM：显式配置为 stub（规则模式）");
            return stub;
        }
        if (props.resolveApiKey() == null) {
            // 无 Key 不阻塞 B0——Key 是配置项不是阻塞项（D5）
            log.warn("灵思 LLM：未检测到 PAMS_AGENT_API_KEY，降级为 stub 规则模式（流程完整可演示）");
            return stub;
        }
        log.info("灵思 LLM：主 provider = deepseek，model = {}", props.getModel());
        return new DeepSeekProvider(props);
    }
}
