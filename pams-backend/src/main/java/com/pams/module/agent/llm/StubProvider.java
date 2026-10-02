package com.pams.module.agent.llm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线规则 provider（业务设计方案 D5 的三级降级末端）。
 *
 * <p><b>"离线可演示是硬指标，这条必须实现，不能只在文档里写。"</b>
 *
 * <p>实现方式：关键词 → 固定工具链。它要覆盖：
 * <ul>
 *   <li>5 类高频问答（待办 / 活动任务 / 材料 / 通知 / 排班空闲）</li>
 *   <li>1 次文书生成（推文草稿）</li>
 *   <li>1 次批量确认（素拓批量加分）</li>
 * </ul>
 *
 * <p>它是<b>纯规则</b>的，因此其答复会被前端明确标注为「规则模式」——
 * 不能在用户不知情时用模板冒充 LLM 输出（灵思 §3.5.1）。
 */
@Component
public class StubProvider implements LlmProvider {

    @Override
    public String name() { return "stub"; }

    @Override
    public boolean supportsToolCalling() { return true; }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String userText = lastUserText(request);
        List<ToolCallRequest> calls = route(userText);

        // 已执行过工具（有 tool 角色消息）→ 直接给兜底话术，不再无限循环调工具
        boolean alreadyRanTools = request.messages().stream()
                .anyMatch(m -> "tool".equalsIgnoreCase(m.role()));
        if (alreadyRanTools || calls.isEmpty()) {
            return ChatResponse.stub(fallback(userText, request));
        }
        return new ChatResponse("", calls, name(), true);
    }

    private String lastUserText(ChatRequest req) {
        for (int i = req.messages().size() - 1; i >= 0; i--) {
            ChatMessage m = req.messages().get(i);
            if ("user".equalsIgnoreCase(m.role())) return m.content() == null ? "" : m.content();
        }
        return "";
    }

    /**
     * 关键词路由表。顺序即优先级——先匹配到的先算，因此把更具体的意图放前面
     * （"起草推文"要先于"推文"命中，否则会被检索类截走）。
     */
    private List<ToolCallRequest> route(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<ToolCallRequest> out = new ArrayList<>();

        // —— 写类：批量加分（验证确认门禁链路） ——
        if (hasAny(t, "加分", "素拓分", "批量加分")) {
            out.add(call("previewCreditBatch", mapOf("activityId", Long.valueOf(1))));
            return out;
        }
        // —— 写类：文书起草 ——
        if (hasAny(t, "起草", "写稿", "推文", "新闻稿", "公告草稿")) {
            out.add(call("draftArticleFromActivity", mapOf("activityId", Long.valueOf(1))));
            return out;
        }
        // —— 写类：议程 / 排班 ——
        if (hasAny(t, "排班")) {
            out.add(call("generateActivitySchedule", mapOf("activityId", Long.valueOf(1))));
            return out;
        }
        // —— 读类：待办（最高频，放最前） ——
        if (hasAny(t, "待办", "待我", "等我", "要处理", "待审", "未读")) {
            out.add(call("queryMyPendingWork", Map.of()));
            return out;
        }
        // —— 读类：材料检索 ——
        if (hasAny(t, "材料", "找一下", "文件", "资料", "策划书在哪")) {
            out.add(call("searchMaterials", mapOf("keyword", extractKeyword(text))));
            return out;
        }
        // —— 读类：共同空闲 ——
        if (hasAny(t, "有空", "空闲", "开会", "时间冲突")) {
            out.add(call("queryCommonFreeTime", Map.of()));
            return out;
        }
        // —— 读类：考勤 ——
        if (hasAny(t, "考勤", "缺勤", "请假", "出勤")) {
            out.add(call("queryAttendance", Map.of()));
            return out;
        }
        // —— 读类：签到统计 ——
        if (hasAny(t, "签到", "签到率", "应签", "已签")) {
            out.add(call("queryRosterSigninStats", mapOf("activityId", Long.valueOf(1))));
            return out;
        }
        // —— 读类：党务（脱敏） ——
        if (hasAny(t, "党员", "发展进度", "积极分子", "预备党员")) {
            out.add(call("queryPartyMemberProgress", mapOf("keyword", extractKeyword(text))));
            return out;
        }
        // —— 读类：素拓台账 ——
        if (hasAny(t, "素拓", "加了多少分", "积分")) {
            out.add(call("queryCreditRecords", Map.of()));
            return out;
        }
        // —— 读类：通知 ——
        if (hasAny(t, "通知", "公告")) {
            out.add(call("queryNotifications", Map.of()));
            return out;
        }
        // —— 读类：任务逾期 ——
        if (hasAny(t, "逾期", "任务", "进度", "甘特")) {
            out.add(call("queryActivityTasks", Map.of()));
            return out;
        }
        // —— 读类：稿件 ——
        if (hasAny(t, "稿件", "审核", "推文状态")) {
            out.add(call("searchArticles", Map.of()));
            return out;
        }
        // —— 读类：活动概览（兜底命中率最高，但仍需有关键词） ——
        if (hasAny(t, "活动", "本月", "最近", "有什么")) {
            out.add(call("queryActivityOverview", Map.of()));
            return out;
        }
        return out;
    }

    private ToolCallRequest call(String name, Map<String, Object> args) {
        return new ToolCallRequest("stub_" + name, name, args);
    }

    /** 兜底话术：明确告知当前是规则模式，并给出可用问法（避免用户以为"坏了"） */
    private String fallback(String userText, ChatRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前为**规则模式**（未接入大模型），已按关键词匹配为你执行了可用能力。\n\n");
        if (!userText.isBlank()) {
            sb.append("你问的是：「").append(userText.length() > 60 ? userText.substring(0, 60) + "…" : userText).append("」\n\n");
        }
        sb.append("规则模式下支持这些问法：\n")
          .append("- 最近有哪些事等着我处理？\n")
          .append("- 找一下 XX 活动的材料\n")
          .append("- 下周三下午部门开会有空吗？\n")
          .append("- 这个月有哪些活动？\n")
          .append("- 有哪些任务逾期了？\n")
          .append("- 帮我把这次活动的推文起草了\n")
          .append("- 给签到人员批量加素拓分\n");
        return sb.toString();
    }

    private boolean hasAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    /** 从"找一下第四十期培训班的材料"里粗略抽出关键词 */
    private String extractKeyword(String text) {
        if (text == null) return "";
        String k = text
                .replaceAll("(?i)(帮我|请|麻烦|找一下|找找|查一下|查询|搜索|有没有|在哪|在哪里)", "")
                .replaceAll("(的材料|材料|的文件|文件|的策划书|策划书|的资料|资料)", "")
                .replaceAll("[？?。！!，,、]", "")
                .trim();
        return k.length() > 30 ? k.substring(0, 30) : k;
    }

    private Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
