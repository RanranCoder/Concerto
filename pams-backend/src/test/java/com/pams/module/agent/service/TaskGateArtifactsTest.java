package com.pams.module.agent.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 产物映射（UI v2 §C「产物卡」）的确定性回归。
 *
 * <p>这里锁的是一条**出网契约**：{@code CommitResult.references} 的键名是
 * {@code type / id / label / link}，而前端产物卡吃的是
 * {@code entityType / entityId / name / path}。两套键名必须由 {@link TaskGate#toArtifacts}
 * 精确转换 —— 转换写错了（比如 id 忘了转、label 取错字段），前端会静默渲染出
 * 一张「名字空、ID 空」的产物卡，页面不报错、控制台不报警，只有用户发现"改了什么都看不到"。
 *
 * <p><b>同时锁住"不加字段"这条约束</b>：{@code agent_task.result_json} 里已经持久化着
 * 老键名的 references，历史任务永远按老格式读。所以只能做映射，不能改 references 本身，
 * 否则老任务的产物卡会全部渲染不出来（D11 第三类语义变更）。
 */
class TaskGateArtifactsTest {

    private static Map<String, Object> commitResult(List<Map<String, Object>> refs) {
        Map<String, Object> rj = new LinkedHashMap<>();
        rj.put("status", "SUCCESS");
        rj.put("affectedCount", 41);
        rj.put("failedCount", 0);
        rj.put("message", "已写入 41 条");
        rj.put("detail", Map.of("created", 41, "failed", 0));
        rj.put("references", refs);
        return rj;
    }

    @Test
    void mapsReferenceKeysToArtifactShape() {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", "activity");
        ref.put("id", 412);
        ref.put("label", "开班仪式 · 活动任务");
        ref.put("link", "/activity/412/tasks");

        List<Map<String, Object>> artifacts = TaskGate.toArtifacts(commitResult(List.of(ref)));

        assertThat(artifacts).hasSize(1);
        Map<String, Object> a = artifacts.get(0);
        assertThat(a)
                .containsEntry("entityType", "activity")
                .containsEntry("entityId", 412)
                .containsEntry("name", "开班仪式 · 活动任务")
                .containsEntry("path", "/activity/412/tasks");
        assertThat(a).as("不得把 references 的原始键名一起漏出去").doesNotContainKey("label");
    }

    @Test
    void mapsMultipleReferencesInOrder() {
        List<Map<String, Object>> refs = new ArrayList<>();
        refs.add(Map.of("type", "activity", "id", 1, "label", "A", "link", "/a"));
        refs.add(Map.of("type", "member", "id", 2, "label", "B", "link", "/b"));

        List<Map<String, Object>> artifacts = TaskGate.toArtifacts(commitResult(refs));

        assertThat(artifacts).extracting(m -> m.get("name")).containsExactly("A", "B");
        assertThat(artifacts).extracting(m -> m.get("entityType")).containsExactly("activity", "member");
    }

    @Test
    void nullMissingOrNonListReferencesYieldEmptyList() {
        assertThat(TaskGate.toArtifacts(null)).isEmpty();
        assertThat(TaskGate.toArtifacts(Map.of())).isEmpty();
        assertThat(TaskGate.toArtifacts(Map.of("references", "not-a-list"))).isEmpty();
        assertThat(TaskGate.toArtifacts(Map.of("references", List.of()))).isEmpty();
    }

    @Test
    void toleratesMalformedRows() {
        // 允许 null 行与非 Map 行（历史数据/工具写错类型时不能让整张卡崩掉）
        List<Object> raw = new ArrayList<>();
        raw.add(null);
        raw.add("garbage");
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("type", "announcement");
        ok.put("id", 9L);
        ok.put("label", "开班通知");
        raw.add(ok);

        List<Map<String, Object>> artifacts = TaskGate.toArtifacts(Map.of("references", raw));

        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0)).containsEntry("entityType", "announcement").containsEntry("name", "开班通知");
    }

    @Test
    void missingKeysBecomeNullRatherThanAbsent() {
        // 前端 sanitizeArtifacts 依赖 name 兜底；这里保证键存在，值可能是 null，
        // 避免前端出现 undefined 与 null 两种空态
        List<Map<String, Object>> artifacts =
                TaskGate.toArtifacts(Map.of("references", List.of(Map.of("type", "member"))));

        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0)).containsKeys("entityType", "entityId", "name", "path");
        assertThat(artifacts.get(0).get("name")).isNull();
    }
}
