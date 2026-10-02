package com.pams.module.agent.config;

import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.llm.StubProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺 API Key 时 agent 装配必须优雅降级（2026-09-22 审计 #10 修复配套）：
 * 不得在启动/装配期抛异常导致整个应用起不来，而是降级为 stub 规则模式。
 */
class LlmConfigMissingKeyTest {

    @Test
    void primaryProvider_withoutApiKey_degradesToStubWithoutThrowing() {
        // 覆盖 resolveApiKey：无论测试环境是否恰好注入了 PAMS_AGENT_API_KEY，
        // 本用例只验证「解析不到 Key」这条路径的行为。
        AgentProperties props = new AgentProperties() {
            @Override
            public String resolveApiKey() {
                return null;
            }
        };
        LlmConfig config = new LlmConfig();
        StubProvider stub = config.stubProvider();

        // 若装配期抛异常（要求 Key 非空/构造即炸），本行即失败——等价于应用无法启动
        LlmProvider provider = config.primaryProvider(props, stub);

        assertThat(provider).isSameAs(stub);
    }
}
