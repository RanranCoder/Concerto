package com.pams.module.knowledge.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Business JSON must become readable source text before chunking or truncation. */
public final class PlanSectionText {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> LABELS = Map.of(
            "step", "环节", "detail", "内容", "time", "时间", "item", "项目",
            "quantity", "数量", "unitPrice", "单价", "totalPrice", "合计", "unit", "单位", "remark", "备注");
    private PlanSectionText() {}

    public static String readable(String text) {
        if (text == null || !text.stripLeading().startsWith("[")) return text;
        try {
            JsonNode rows = MAPPER.readTree(text);
            if (!rows.isArray()) return text;
            List<String> lines = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!row.isObject() || (!row.has("step") && !row.has("item"))) return text;
                List<String> fields = new ArrayList<>();
                var names = row.fieldNames();
                while (names.hasNext()) {
                    String key = names.next();
                    JsonNode value = row.get(key);
                    if (value != null && !value.isNull()) {
                        fields.add(LABELS.getOrDefault(key, key) + "：" +
                                (value.isValueNode() ? value.asText() : value.toString()));
                    }
                }
                lines.add((lines.size() + 1) + ". " + String.join("；", fields));
            }
            return lines.isEmpty() ? text : String.join("\n", lines);
        } catch (Exception ignored) {
            return text; // Ordinary prose and old partial chunks remain recoverable.
        }
    }
}
