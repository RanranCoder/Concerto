package com.pams.module.agent.config;

import com.pams.module.agent.llm.*;
import org.springframework.context.annotation.*;

@Configuration
public class LlmConfig {
    @Bean public StubProvider stubProvider() { return new StubProvider(); }
    public LlmProvider primaryProvider(AgentProperties props, StubProvider stub) {
        if ("stub".equals(props.getProvider()) || props.resolveApiKey() == null || props.getModel() == null || props.getModel().isBlank()) return stub;
        var endpoint = new ModelEndpoint(props.getProtocol(), props.getBaseUrl(), props.resolveApiKey(), props.getModel(), props.isSummaryEnabled());
        return "openai_responses".equals(endpoint.protocol()) ? new OpenAiResponsesProvider(endpoint, props.getTimeoutMs())
                : new OpenAiChatCompletionsProvider(endpoint, props.getTimeoutMs());
    }
    @Bean public LlmProvider primaryProvider(AgentProperties props, StubProvider stub, com.pams.module.agent.provider.ProviderHttpClient http) {
        if ("stub".equals(props.getProvider()) || props.resolveApiKey() == null || props.getModel() == null || props.getModel().isBlank()) return stub;
        var endpoint = new ModelEndpoint(props.getProtocol(), props.getBaseUrl(), props.resolveApiKey(), props.getModel(), props.isSummaryEnabled());
        return "openai_responses".equals(endpoint.protocol()) ? new OpenAiResponsesProvider(endpoint, props.getTimeoutMs(), http.client())
                : new OpenAiChatCompletionsProvider(endpoint, props.getTimeoutMs(), http.client());
    }
}
