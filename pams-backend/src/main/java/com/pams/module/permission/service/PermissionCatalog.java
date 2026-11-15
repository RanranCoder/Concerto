package com.pams.module.permission.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

/** Versioned business vocabulary with immutable inputs for previous migrations. */
@Component
public class PermissionCatalog {
  private record Definition(JsonNode permission, int catalogVersion) {}

  private final Map<String, Definition> definitions = new HashMap<>();
  private static final Map<String, String> GROUPS =
      Map.ofEntries(
          Map.entry("activity", "活动管理"), Map.entry("task", "任务执行"),
          Map.entry("agenda", "活动议程"), Map.entry("plan", "策划编审"),
          Map.entry("score", "活动评分"), Map.entry("seat", "座位安排"),
          Map.entry("checkin", "活动签到"), Map.entry("schedule", "排班管理"),
          Map.entry("attendance", "考勤核验"), Map.entry("course", "课表与空闲"),
          Map.entry("member", "成员资料"), Map.entry("party", "党务台账"),
          Map.entry("youth", "团务工作"), Map.entry("volunteer", "志愿服务"),
          Map.entry("content", "新闻与宣传"), Map.entry("article", "推文编审"),
          Map.entry("material", "材料档案"), Map.entry("template", "公共模板"),
          Map.entry("quality", "素拓加分"), Map.entry("chat", "群聊资料"),
          Map.entry("notice", "组织公告"), Map.entry("file", "文件读取"),
          Map.entry("kb", "知识库"), Map.entry("notification", "业务通知"));

  public PermissionCatalog() {
    try {
      var mapper = new ObjectMapper();
      for (String name : List.of("permission-catalog-v3.json", "permission-catalog-v4.json")) {
        try (var stream = getClass().getResourceAsStream("/authorization/" + name)) {
          var catalog = mapper.readTree(Objects.requireNonNull(stream));
          int version = catalog.path("version").asInt();
          catalog.path("permissions").forEach(
              p -> definitions.put(p.path("code").asText(), new Definition(p, version)));
        }
      }
      for (String code : definitions.keySet()) verify(code, new HashSet<>(), new HashSet<>());
    } catch (Exception e) {
      throw new IllegalStateException("权限目录无法加载", e);
    }
  }

  public List<String> dependencies(String code, Set<String> known) {
    var definition = definitions.get(code);
    if (definition == null) throw new IllegalStateException("未定义的业务权限：" + code);
    List<String> result = new ArrayList<>();
    definition.permission().path("dependencies").forEach(p -> result.add(p.asText()));
    return result;
  }

  public boolean contains(String code) {
    return definitions.containsKey(code);
  }

  private void verify(String code, Set<String> visiting, Set<String> done) {
    if (done.contains(code)) return;
    if (!definitions.containsKey(code) || !visiting.add(code))
      throw new IllegalStateException("权限依赖缺失或循环：" + code);
    var p = definitions.get(code).permission();
    if (!p.has("dependencies") || p.path("description").asText().isBlank())
      throw new IllegalStateException("权限缺少依赖或说明：" + code);
    p.path("dependencies").forEach(d -> verify(d.asText(), visiting, done));
    visiting.remove(code);
    done.add(code);
  }

  public Map<String, Object> describe(String code, String module, Set<String> known) {
    var definition = definitions.get(code);
    if (definition == null) throw new IllegalStateException("未定义的业务权限：" + code);
    var p = definition.permission();
    boolean risk = p.path("risk").asBoolean();
    return Map.of(
        "groupName",
        GROUPS.getOrDefault(module, module),
        "description",
        p.path("description").asText(),
        "dependencies",
        dependencies(code, known),
        "risk",
        risk,
        "catalogVersion",
        definition.catalogVersion());
  }
}
