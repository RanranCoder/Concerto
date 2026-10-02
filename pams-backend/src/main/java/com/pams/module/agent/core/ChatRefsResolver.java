package com.pams.module.agent.core;

import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.service.ActivityService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户「附加上下文」的解析与注入（UI v2 §E 输入框 `+` 上下文选择的 后端半边）。
 *
 * <p><b>它解决什么</b>：用户口述活动名称时，{@link com.pams.module.agent.tool.ActivityResolver}
 * 已经能做到"精确同名唯一才拍板"，但重名、简写、记不清全称的场景仍会退回候选清单，
 * 多一轮往返。让用户在输入框里<b>点选</b>实体，等于把消歧提前到零成本的一步——
 * 选中的 ID 由系统产生，不是模型猜的，也不是用户口述的。
 *
 * <p><b>为什么必须注入 LLM 上下文而不是只存库</b>：模型看不见的东西就不会用。
 * 用户选了「第四十期培训班」然后说"给它排班"，模型必须知道"它"指什么，
 * 否则还是会去按名称解析、又落回候选清单——上下文选择就白做了。
 *
 * <p><b>为什么注记要写「以此为准」</b>：用户消息里可能同时出现口述名称与附加引用，
 * 二者冲突时必须有一个明确的裁决规则，否则模型行为不可预测。
 *
 * <p><b>刻意不放进上下文的内容</b>：只注入活动的<b>身份信息</b>（名称/状态/起止时间/地点）。
 * 名册、联系方式这类数据仍必须由工具在用户真的问起时查询并过
 * {@link PolicyGuard#egress} —— 上下文注入不是绕过数据分级与脱敏的后门（铁律 ③）。
 *
 * <p><b>2026-09-14 补可见性判定（权限审计 A1）</b>：此前 {@link #fetch} 直调
 * {@code ActivityService.getEntity(id)}，<b>不经过 {@code ToolRegistry}、不校验权限、
 * 不留审计</b>。这意味着前端传什么 ID 就能解析什么活动 —— 一个不受任何既有机制约束的数据出口。
 *
 * <p>判定口径与 `ActivityController` 的 `GET /api/activities` 保持一致（`activity:view`），
 * 因为两处读的是同一份数据，口径必须同源，否则会出现"能问出来但页面打不开"这类裂缝。
 */
@Component
public class ChatRefsResolver {

    /** 单条消息最多附加几个引用：再多说明用户想做的是批量操作，该走批量工具而不是堆上下文 */
    public static final int MAX_REFS = 5;

    /**
     * 活动可见性口径。
     *
     * <p>与 {@code ActivityController#page} 的 {@code @PreAuthorize} 同码。
     * 实践中 7 个角色的权限种子都含该码，所以这是"把门变成真的"而不是"收紧访问"。
     */
    private static final String ACTIVITY_VIEW = "activity:view";

    private final ActivityService activityService;

    public ChatRefsResolver(ActivityService activityService) {
        this.activityService = activityService;
    }

    /**
     * 解析结果。
     *
     * @param note       注入 LLM 的 system 注记；没有可注入内容时为 {@code null}
     * @param references 回传给前端展示/落库的引用（与其他工具的 references 同构，前端零改动）
     */
    public record Resolved(String note, List<Map<String, Object>> references) {
        public boolean empty() { return note == null; }
    }

    /**
     * 把前端选中的 {@code [{type, id}]} 解析成可注入的注记。
     *
     * <p>对脏数据的态度：<b>跳过而不是报错</b>——上下文附加是锦上添花的能力，
     * 一个类型不支持的引用不该让整轮问答失败；但也必须在注记里说明"已忽略"，
     * 否则用户以为附加成功了、模型却没看见，这是最隐蔽的失效方式（D11 ③ 类）。
     *
     * <p><b>对"无权访问"与"不存在"的处理</b>：两者<b>必须给出同一句话</b>。
     * 如果分开提示，攻击者可以拿"无权访问"当作存在性证明去枚举 ID
     * （知识库方案的 G7 是同一个问题）。安全与可读性冲突时，这里选安全。
     *
     * @param refs 前端传来的引用（形状已在 Controller 收窄）
     * @param ctx  执行上下文（取权限码；为 {@code null} 时保守拒绝）
     */
    public Resolved resolve(List<Map<String, Object>> refs, ToolContext ctx) {
        if (refs == null || refs.isEmpty()) return new Resolved(null, List.of());

        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> references = new ArrayList<>();

        for (Map<String, Object> ref : refs) {
            if (lines.size() >= MAX_REFS) break;
            if (ref == null) continue;
            String type = str(ref.get("type"));
            Long id = toId(ref.get("id"));
            if (type == null || id == null) continue;

            if (!"activity".equals(type)) {
                lines.add("· 类型为「" + type + "」的引用暂不支持作为上下文，已忽略。");
                continue;
            }

            // 先判可见性再取数据：拿不到就不取，避免"取了再判断"把内容带进内存
            Activity a = canViewActivity(ctx) ? fetch(id) : null;
            if (a == null) {
                lines.add(notAccessible(id));
                continue;
            }

            StringBuilder sb = new StringBuilder("· 活动「").append(a.getName()).append("」（ID ").append(id).append("）");
            if (a.getStatus() != null) sb.append(" · 状态 ").append(a.getStatus());
            if (a.getStartDate() != null) sb.append(" · ").append(a.getStartDate());
            if (a.getEndDate() != null) sb.append(" ~ ").append(a.getEndDate());
            if (a.getLocation() != null && !a.getLocation().isBlank()) sb.append(" · ").append(a.getLocation());
            lines.add(sb.toString());

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", "activity");
            r.put("id", id);
            r.put("label", a.getName());
            r.put("link", "/activities/" + id);
            references.add(r);
        }

        if (lines.isEmpty()) return new Resolved(null, List.of());

        StringBuilder note = new StringBuilder();
        note.append("【用户附加的上下文】\n");
        note.append("用户在本条消息中从系统里明确选定了以下对象（ID 由系统生成，可信，无需再按名称消歧）：\n");
        for (String l : lines) note.append(l).append('\n');
        note.append("若用户提到的名称与上面指定的对象冲突，以上面指定的为准。");

        return new Resolved(note.toString().trim(), references);
    }

    /* ------------------------------------------------------------------ helpers */

    /**
     * 活动可见性判定。
     *
     * <p>{@code ctx == null} 时保守拒绝：这个方法被"上下文注入"这类旁路调用，
     * 一旦将来有人在不持有上下文的路径里调它，静默放行会变成新的越权口。
     */
    private boolean canViewActivity(ToolContext ctx) {
        return ctx != null && ctx.hasPermission(ACTIVITY_VIEW);
    }

    /** 统一话术：无权与不存在不可区分（防存在性枚举） */
    private String notAccessible(Long id) {
        return "· 活动 ID " + id + " 不存在或无权访问，已忽略。";
    }

    private Activity fetch(Long id) {
        try {
            return activityService.getEntity(id);
        } catch (Exception e) {
            // 引用解析失败不影响主链路：这轮问答少了点上下文而已
            return null;
        }
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private Long toId(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
