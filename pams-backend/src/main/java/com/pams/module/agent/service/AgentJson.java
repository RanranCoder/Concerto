package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** MySQL JSON 对象及 H2 的 JSON 字符串包装使用相同的读侧契约。 */
public final class AgentJson {
    private AgentJson() {}
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(ObjectMapper mapper, String value) throws java.io.IOException {
        JsonNode node = readTree(mapper,value);
        if (node == null || !node.isObject()) throw new java.io.IOException("任务 JSON 必须是对象");
        return new LinkedHashMap<>(mapper.convertValue(node, Map.class));
    }
    public static JsonNode readTree(ObjectMapper mapper,String value) throws java.io.IOException {
        JsonNode node = mapper.readTree(value);
        for (int i=0; i<3 && node != null && node.isTextual(); i++) node=mapper.readTree(node.asText());
        return node;
    }
}
