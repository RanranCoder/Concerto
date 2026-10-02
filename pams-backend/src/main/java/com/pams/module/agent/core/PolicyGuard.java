package com.pams.module.agent.core;

import com.pams.module.agent.entity.AgentMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 出网闸口 + 写时脱敏（业务设计方案 D4）。
 *
 * <p>位置：横切在「工具结果 → 组装 prompt」之间，是<b>唯一</b>的出网闸口。
 *
 * <p>两条职责：
 * <ol>
 *   <li><b>出网裁决</b>：{@code normal} 直接进入；{@code sensitive} 字段级遮罩；{@code restricted} 拦截。</li>
 *   <li><b>写时脱敏</b>：{@code agent_message} 只落摘要而非明文——这样连"90 天清理任务"都省掉，
 *       因为敏感内容<b>从未被写入</b>（决策 6）。</li>
 * </ol>
 */
@Slf4j
@Component
public class PolicyGuard {

    /** 手机号：中间 4 位遮罩 */
    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");
    /** 身份证：保留前 6 后 4 */
    private static final Pattern ID_CARD = Pattern.compile("(\\d{6})\\d{8}(\\d{3}[\\dXx])");
    /**
     * 学号：保留前 4 后 2。
     *
     * <p><b>为什么加了前后 {@code (?<!\d)} / {@code (?!\d)}</b>：学号与手机号、金额、编号
     * 都是"一串数字"，没有边界约束就会互相咬。原实现里这个 pattern 定义了却从未被使用
     * （属于"以为有防护其实没有"的另一处），2026-09-14 一并接上。
     *
     * <p>长度范围取 10–12 位（4 + 4~6 + 2），与本校学号实际位数对齐；
     * 更长的数字串（如 18 位身份证）由 {@link #ID_CARD} 先处理，不会被这里误伤。
     */
    private static final Pattern STUDENT_NO = Pattern.compile("(?<!\\d)(\\d{4})\\d{4,6}(\\d{2})(?!\\d)");

    /**
     * 裁决该工具结果能否进入 LLM 上下文。
     *
     * @return 可出网时返回（已脱敏的）文本；restricted 返回 null
     */
    public String egress(Sensitivity level, ToolResult result) {
        if (result == null) return null;
        return egressText(level, result.llmText());
    }

    /**
     * 出网裁决（文本形态）。**这是唯一闸口**，调用方不得绕过。
     *
     * <p><b>2026-09-14 修复（审计 A2）</b>：此前的实现是"算了但没用"——
     * {@code AgentOrchestrator} 调 {@link #egress} 拿到结果后，只把它当作
     * "是不是 restricted" 的 {@code null} 判断，真正进上下文的文本却取自
     * {@code tool.toLlmText(ctx, result)}（默认实现 = {@link ToolResult#llmText()}，即**原文**）。
     * 后果：{@code SENSITIVE} 的 {@link #mask} 从未生效，而 {@code RESTRICTED} 因为走
     * {@code egress == null} 分支反而正常拦截 —— 一个安全闸口接了线却没通电，且不报任何错。
     *
     * <p><b>职责边界（勿混淆）</b>：
     * <ul>
     *   <li>工具/Service 负责<b>字段语义级</b>脱敏 —— 例如
     *       {@code PartyMemberService.page(..., staff)} 知道哪个字段是身份证，
     *       也知道"姓名该不该给"。这类判断只有知道语义的地方能做。</li>
     *   <li>本闸口负责<b>模式级</b>兜底 —— 用正则兜住手机号/身份证/学号这类
     *       "无论出现在哪个字段都是敏感信息"的内容。</li>
     * </ul>
     * 所以本方法<b>不</b>做姓名遮罩：中文姓名无法用正则可靠识别，
     * 强行处理只会把正常文本改坏。姓名级遮罩请用 {@link #maskName}，
     * 并且只在<b>明确知道该字符串是人名</b>的调用点使用。
     */
    public String egressText(Sensitivity level, String text) {
        if (level == null) level = Sensitivity.NORMAL;

        if (!level.canEgress()) {
            // restricted：内容根本不出网，由模板渲染固定话术（D4）
            log.debug("PolicyGuard 拦截 restricted 内容，不出网");
            return null;
        }
        if (level == Sensitivity.SENSITIVE) {
            return mask(text);
        }
        return text;
    }

    /**
     * 字段级遮罩（D4：sensitive 脱敏后允许出网）。
     * 服务端强制，不依赖 LLM 自觉。
     *
     * <p><b>规则顺序是有讲究的，不能随意调换</b>（2026-09-14 实测踩到）：
     * <pre>
     * 原顺序：先 PHONE 再 ID_CARD
     * 身份证 11010119900307721X 中的 "19900307721" 恰好满足手机号的 1[3-9]\d\d{4}\d{4}
     *   → 先被手机号规则吃成 110101199****7721X
     *   → 身份证里已无连续 8 位数字，ID_CARD 规则**再也匹配不上**
     *   → 结果：证件号只被遮住了中间一段，前 6 位与后 4 位之外的仍可辨识
     * </pre>
     * 所以顺序固定为 <b>ID_CARD → PHONE → STUDENT_NO</b>：
     * 长模式优先，且每条规则执行后产生的 {@code *} 天然阻断了后续规则的重叠匹配。
     *
     * <p>这套规则注定是"兜底"而非"完备"——见 {@link #egressText} 的职责边界说明：
     * 字段语义级脱敏（谁是人名、哪个字段是住址）必须在知道语义的地方做。
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = ID_CARD.matcher(text).replaceAll("$1********$2");
        out = PHONE.matcher(out).replaceAll("$1****$2");
        out = STUDENT_NO.matcher(out).replaceAll("$1****$2");
        return out;
    }

    /** 姓名遮罩：张** 形式（脱敏展示用，不用于出网裁决） */
    public String maskName(String name) {
        if (name == null || name.isBlank()) return name;
        if (name.length() == 1) return name;
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /**
     * 决定写入 {@code agent_message} 的内容（决策 6 的"写时脱敏"）。
     *
     * <table>
     *   <tr><td>normal</td><td>完整文本</td></tr>
     *   <tr><td>sensitive</td><td><b>仅摘要</b></td></tr>
     *   <tr><td>restricted</td><td>不落内容，仅占位</td></tr>
     * </table>
     */
    public String forStorage(Sensitivity level, String fullText, String summary) {
        if (level == null) level = Sensitivity.NORMAL;
        return switch (level) {
            case NORMAL -> fullText;
            case SENSITIVE -> summary == null ? "（敏感内容已按策略仅存摘要）" : summary;
            case RESTRICTED -> "（该信息属受限数据，未在对话中留存；请在对应业务页面查看）";
        };
    }

    /** 该敏感级是否允许内容落库 */
    public boolean contentPersistable(Sensitivity level) {
        return level == Sensitivity.NORMAL;
    }

    /** 工具入参落库前的摘要（D9：args_digest 只落 hash，不落明文） */
    public String digest(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        try {
            String json = args.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()))
                    .reduce((a, b) -> a + "&" + b).orElse("");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16 && i < h.length; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 入参的展示摘要（脱敏后，用于工具调用流水中的 args_json） */
    public Map<String, Object> sanitizeArgs(Map<String, Object> args) {
        if (args == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            // 身份证/电话类键名直接遮罩；其余保留（值里的长数字串再过一遍 mask）
            if (k != null && (k.contains("idCard") || k.contains("phone") || k.contains("mobile"))) {
                out.put(k, "***");
            } else if (v instanceof String s) {
                out.put(k, mask(s));
            } else {
                out.put(k, v);
            }
        }
        return out;
    }

    /** restricted 工具的固定话术（D4：不经 LLM，由模板渲染） */
    public String restrictedNotice(String subject, String deepLink) {
        return "该信息受权限保护，无法在此展示。\n\n"
                + subject + "属于受限数据，仅可在业务页面查看。"
                + (deepLink == null ? "" : "\n\n前往查看：" + deepLink);
    }

    /** references 深链（p6 受限块用；后端给出，前端不自行拼路径） */
    public Map<String, Object> reference(String type, Object id, String label, String link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("label", label);
        if (link != null) m.put("link", link);
        return m;
    }

    public List<Map<String, Object>> refs(Map<String, Object>... items) {
        return List.of(items);
    }

    /** 便捷：new AgentMessage 时用的角色常量转发（避免各处硬编码字符串） */
    public static String assistantRole() { return AgentMessage.ROLE_ASSISTANT; }
}
