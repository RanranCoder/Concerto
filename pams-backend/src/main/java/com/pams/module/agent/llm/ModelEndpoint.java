package com.pams.module.agent.llm;

/** Immutable task snapshot. Never expose credentials through toString or API responses. */
public record ModelEndpoint(String protocol, String baseUrl, String apiKey, String model, boolean summary) {
    public ModelEndpoint {
        if (!"openai_chat_completions".equals(protocol) && !"openai_responses".equals(protocol))
            throw new IllegalArgumentException("不支持的 AI 协议");
    }
    @Override public String toString() { return "ModelEndpoint[protocol=" + protocol + ", model=" + model + "]"; }
}
