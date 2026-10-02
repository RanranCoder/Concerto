package com.pams.module.agent.tool;

import com.pams.common.PageResult;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.service.ActivityService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 活动名称 → 活动实体的共享解析组件（业务设计方案 §3.9）。
 *
 * <p><b>为什么需要它</b>：用户在对话里天生只知道活动<b>名称</b>（"给新生党员欢迎会加分"），
 * 不可能报出一串活动 ID。而工具入参过去强制要求 {@code activityId}，
 * 于是 LLM 只能先调"列活动"、再从返回里翻 ID、再二段调用——多一轮往返、且极易翻错。
 * 本组件让工具直接接受名称，在<b>服务端</b>把名称解析成实体，把这一步对用户与 LLM 都隐藏掉。
 *
 * <p><b>解析顺序（重要，写类工具同样复用，必须"绝不猜"）</b>：
 * <ol>
 *   <li><b>ID 直取</b>：{@code activityId} 给了就直接 {@code getEntity}，最精确。</li>
 *   <li><b>名称尾缀 {@code （ID n）} 直取</b>：形如 {@code 新生党员欢迎会（ID 34）}。
 *       这是"上一轮候选列表 → 用户/LLM 回传"的闭环载体——
 *       当用户面对重名活动时，把候选连同 ID 回传，此处直接命中，无需再消歧。</li>
 *   <li><b>精确同名唯一才解析</b>：{@code equalsIgnoreCase} 且<b>仅命中一个</b>时才拍板。
 *       这是为写类工具守的底线——两个同名活动时静默挑一个会导致"分数加错活动"，不可接受。</li>
 *   <li><b>其余一律不给结论</b>：多命中/零命中都返回<b>候选清单 + 可读指引</b>，
 *       让 LLM 带着 {@code 名称（ID n）} 重试。工具<b>从不静默选择</b>。</li>
 * </ol>
 *
 * <p>名称匹配用 {@link ActivityService#page}（对 name/theme 做 LIKE），
 * 仅用于<b>构造候选清单</b>，不用于自动拍板——所以 LIKE 的模糊性不会造成误选。
 *
 * <p>线程无关、无状态：每次调用自带参数，可直接被任意工具注入复用。
 */
@Component
public class ActivityResolver {

    /** schema 里 activityName 字段的统一描述（各工具复用，保证 LLM 看到的措辞一致） */
    public static final String NAME_DESC =
            "活动名称，与 activityId 二选一；不知道活动 ID 时直接传名称，系统会自动匹配。";

    /** 名称尾缀「（ID n）」/「(ID n)」——候选列表回传的闭环标记 */
    private static final Pattern ID_SUFFIX =
            Pattern.compile("^(.*?)[（(]\\s*ID\\s*(\\d+)\\s*[）)]\\s*$", Pattern.CASE_INSENSITIVE);

    /** 候选清单最多展示的条数（避免超长消息；够了，再多用户也懒得看） */
    private static final int CANDIDATE_LIMIT = 20;

    private final ActivityService activityService;

    public ActivityResolver(ActivityService activityService) {
        this.activityService = activityService;
    }

    /**
     * 解析结果。
     *
     * @param activity 解析成功时的活动实体；失败时为 {@code null}
     * @param message  失败时给 LLM/用户的可读说明（含候选清单与重试指引）；成功时为 {@code null}
     */
    public record Resolution(Activity activity, String message) {
        public boolean ok() { return activity != null; }
        public boolean failed() { return activity == null; }
    }

    /**
     * 按 ID 或名称解析活动。二者都给时以 ID 为准。
     *
     * @param activityId   活动 ID，可为 null
     * @param activityName 活动名称，可为 null（与 ID 至少给一个）
     */
    public Resolution resolve(Long activityId, String activityName) {
        // ① ID 直取
        if (activityId != null) {
            Activity a = fetch(activityId);
            return a != null
                    ? new Resolution(a, null)
                    : new Resolution(null, "活动 ID " + activityId + " 不存在，请确认活动后重试。");
        }

        if (activityName == null || activityName.isBlank()) {
            return new Resolution(null,
                    "需要指定活动：请提供活动名称（例如「新生党员欢迎会」）。");
        }
        String raw = activityName.trim();

        // ② 名称尾缀「（ID n）」——候选列表回传的闭环
        Matcher m = ID_SUFFIX.matcher(raw);
        if (m.matches()) {
            long id = Long.parseLong(m.group(2));
            Activity a = fetch(id);
            return a != null
                    ? new Resolution(a, null)
                    : new Resolution(null, "活动 ID " + id + " 不存在，请重新确认活动。");
        }

        // 用 LIKE 拉候选（仅用于消歧，不用于自动拍板）
        List<Map<String, Object>> matched = queryByName(raw);
        if (matched.isEmpty()) {
            return new Resolution(null,
                    "没有找到名称包含「" + raw + "」的活动，请确认活动名称是否正确，"
                            + "也可以先让我列一下最近的活动。");
        }

        // ③ 精确同名（忽略大小写）且唯一 → 拍板
        List<Map<String, Object>> exact = new ArrayList<>();
        for (Map<String, Object> c : matched) {
            Object nm = c.get("name");
            if (nm != null && raw.equalsIgnoreCase(String.valueOf(nm).trim())) exact.add(c);
        }
        if (exact.size() == 1) {
            Long id = toId(exact.get(0).get("id"));
            Activity a = id == null ? null : fetch(id);
            if (a != null) return new Resolution(a, null);
            return new Resolution(null, "活动解析失败，请重试。");
        }

        // ④ 其余一律列候选：精确同名多个 → 只列同名项；无精确同名 → 列全部模糊命中
        List<Map<String, Object>> pool = exact.isEmpty() ? matched : exact;
        return new Resolution(null, buildCandidateMessage(raw, pool));
    }

    /* ------------------------------------------------------------------ helpers */

    /**
     * 取实体：null / 异常都归一为 null，由调用方决定消息。
     * （{@code ActivityService.getEntity} 正常路径抛 {@code BizException} 而非返回 null，
     * 但此处不依赖该实现细节——返回 null 时若直接当成功，就会静默产出"解析成功但活动为空"。）
     */
    private Activity fetch(Long id) {
        try {
            return activityService.getEntity(id);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> queryByName(String keyword) {
        try {
            PageResult<Map<String, Object>> page =
                    activityService.page(keyword, null, null, 1, CANDIDATE_LIMIT);
            return page == null || page.getRecords() == null ? List.of() : page.getRecords();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 构造消歧消息。刻意把每条候选写成「名称（ID n）」，
     * 与 {@link #ID_SUFFIX} 的解析口径对称——LLM 照抄回来即可命中。
     */
    private String buildCandidateMessage(String raw, List<Map<String, Object>> pool) {
        boolean sameName = pool.size() > 1 && pool.stream()
                .allMatch(c -> raw.equalsIgnoreCase(String.valueOf(c.get("name")).trim()));

        StringBuilder sb = new StringBuilder();
        if (sameName) {
            sb.append("有 ").append(pool.size()).append(" 个同名活动「").append(raw).append("」，");
        } else {
            sb.append("名称「").append(raw).append("」匹配到 ").append(pool.size()).append(" 个活动，");
        }
        sb.append("请确认是哪一个，然后以「活动名称（ID 数字）」的形式重新指定，例如「")
          .append(candidateLabel(pool.get(0))).append("」。\n");

        for (Map<String, Object> c : pool) {
            sb.append("· ").append(candidateLabel(c));
            Object start = c.get("startDate");
            if (start != null) sb.append(" · ").append(start);
            Object status = c.get("status");
            if (status != null) sb.append(" · ").append(status);
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String candidateLabel(Map<String, Object> c) {
        Object nm = c.get("name");
        Object id = c.get("id");
        return String.valueOf(nm == null ? "（未命名活动）" : nm) + "（ID " + id + "）";
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
