package com.pams.module.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具参数读取与结果组装的公共工具集。
 *
 * <p>为什么单独抽一类：23 个工具都要做同一件事——
 * <b>从 LLM 给的松散 Map 里稳妥地取值</b>。LLM 可能给字符串 "1" 而不是数字 1、
 * 可能缺字段、可能给空串。集中处理一次，胜过 23 处各写一遍 {@code try/catch}。
 */
public final class ToolArgs {

    private ToolArgs() {}

    public static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    public static String str(Map<String, Object> args, String key, String fallback) {
        String s = str(args, key);
        return s == null || s.isBlank() ? fallback : s;
    }

    /** LLM 常把数字给成字符串，这里做宽容解析 */
    public static Long lng(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static long lng(Map<String, Object> args, String key, long fallback) {
        Long v = lng(args, key);
        return v == null ? fallback : v;
    }

    public static int intVal(Map<String, Object> args, String key, int fallback) {
        Long v = lng(args, key);
        return v == null ? fallback : v.intValue();
    }

    public static boolean bool(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        return "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof List<?> l) return new ArrayList<>(l);
        return List.of();
    }

    /** 从列表元素（可能是字符串或 Map）里抽出 id 列表 */
    public static List<Long> longList(Map<String, Object> args, String key) {
        List<Long> out = new ArrayList<>();
        for (Object o : list(args, key)) {
            if (o instanceof Number n) out.add(n.longValue());
            else if (o != null) {
                try {
                    out.add(Long.parseLong(String.valueOf(o).trim()));
                } catch (Exception ignored) {
                    // LLM 给了不可解析的值：跳过，由工具在结果里说明
                }
            }
        }
        return out;
    }

    /** 可变的、保序的 Map（Map.of 不允许 null 值，工具结果里 null 很常见） */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 组一个列表项（用于 payload.table 渲染） */
    public static Map<String, Object> row(Object... kv) {
        return map(kv);
    }

    public static Map<String, Object> table(String[] columns, List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("columns", List.of(columns));
        m.put("rows", rows);
        return m;
    }

    /** 截断长文本，避免把大量数据塞进 LLM 上下文（D5 成本控制） */
    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 摘要一句话里的列表（"共 N 条，如：a、b、c"） */
    public static String listSummary(List<String> names, int max) {
        if (names == null || names.isEmpty()) return "";
        int n = Math.min(names.size(), max);
        String joined = String.join("、", names.subList(0, n));
        return names.size() > n ? joined + " 等 " + names.size() + " 项" : joined;
    }

    /** JSON Schema 便捷构造 */
    public static Map<String, Object> schema(String type, Map<String, Object> props, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("properties", props == null ? Map.of() : props);
        if (required != null && !required.isEmpty()) m.put("required", required);
        return m;
    }

    public static Map<String, Object> prop(String type, String description) {
        return map("type", type, "description", description);
    }

    /** 无参工具的统一 Schema */
    public static Map<String, Object> noArgs() {
        return schema("object", Map.of(), List.of());
    }
}
