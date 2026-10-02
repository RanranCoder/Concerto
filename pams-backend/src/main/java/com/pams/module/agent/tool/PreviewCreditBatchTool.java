package com.pams.module.agent.tool;

import com.pams.module.activity.dto.SigninRosterVO;
import com.pams.module.activity.service.SigninRosterService;
import com.pams.module.agent.core.AgentActionPlan;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.agent.core.WriteAgentTool;
import com.pams.module.archive.service.CreditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：从活动签到名单批量加分（业务设计方案 §3.5 —— <b>验证 D2 确认门禁链路的主用例</b>）。
 *
 * <p>两阶段：
 * <ol>
 *   <li>{@link #execute} = dry-run：读名单 → 算哪些人会被加分、哪些会因重复而跳过 → 出清单。
 *       <b>绝不写库</b>。</li>
 *   <li>{@link #commit} = 用户确认后真正落库，走 {@code CreditService.batchAddFromActivity}。</li>
 * </ol>
 *
 * <p><b>关键设计点 1 — 跳过项必现</b>（灵思 §4.3）。服务侧 {@code batchAddFromActivity} 内部
 * 会因"同一活动已有该人记录"而跳过，但<b>它只返回计数不返回是谁</b>。
 * 因此工具在 dry-run 阶段自行算出跳过名单——这样用户看到的清单与最终落库结果一致，
 * 而不是"确认时说 50 人、执行后只加了 38 人"。
 *
 * <p><b>关键设计点 1.5 — 名单必须先按姓名去重</b>（实测踩坑）。
 * 同一个人的名册行可能有多条，且字段集不同（历史重复导入 / 简版与详版并存）。
 * 而 {@code SigninRosterService.matches()} 的口径是"名册行所有非空字段都要与签到记录相等"，
 * 于是简版行判为<b>已签到</b>、详版行（含签到记录里没有的字段，如"是否服从调剂"）判为<b>未签到</b>。
 * 若不去重就直接出清单，会出现<b>同一个人既在"将加分"又在"将跳过（未签到）"</b>的自相矛盾清单。
 * 这里按姓名聚合，取"任一行已签到即已签到"的最宽口径，并把重复行数如实写进明细。
 * <i>（根因在 {@code SigninRosterService} 的匹配口径，属既有问题，不在本工具内改。）</i>
 *
 * <p><b>关键设计点 2 — 执行参数必须可还原</b>。{@code commit} 只拿得到
 * {@link AgentActionPlan}（它由 {@code plan_json} 反序列化而来），拿不到原始的 LLM 入参。
 * 因此 dry-run 时把执行参数<b>结构化</b>写进 {@code detailLines} 的一个隐藏标记行
 * （{@code __args__:{json}}），commit 时解析回来。
 * <b>不用正则从中文文案里抠数字</b>——那对文案改动毫无抵抗力，是典型的隐性耦合。
 *
 * <p>权限码 {@code quality:activity_add}；数据级 normal。
 */
@Component
public class PreviewCreditBatchTool implements WriteAgentTool {

    private static final String PROJECT_DEFAULT = "活动参与";
    /** detailLines 里的参数载体标记（前端渲染时会隐藏以此开头的行） */
    private static final String ARGS_MARK = "__args__:";

    private final SigninRosterService signinRosterService;
    private final CreditService creditService;
    private final ActivityResolver activityResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public PreviewCreditBatchTool(SigninRosterService signinRosterService, CreditService creditService,
                                  ActivityResolver activityResolver) {
        this.signinRosterService = signinRosterService;
        this.creditService = creditService;
        this.activityResolver = activityResolver;
    }

    @Override
    public String name() { return "previewCreditBatch"; }

    @Override
    public String label() { return "批量加分"; }

    @Override
    public String runningLabel() { return "正在核对签到名单…"; }

    @Override
    public String description() {
        return "为某个活动的签到人员批量添加素拓分。"
                + "调用后会生成一份待确认清单（含将加分的人和会自动跳过的人），"
                + "需要用户确认后才真正写入。适用于'给签到的同学加素拓分''这次活动的参与分加上'等指令。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "来源活动 ID，可省略；与 activityName 二选一"),
                "activityName", ToolArgs.prop("string", ActivityResolver.NAME_DESC),
                "credit", ToolArgs.prop("number", "每人加分值，如 0.5，必填"),
                "project", ToolArgs.prop("string", "加分项目名称，默认'活动参与'"),
                "remark", ToolArgs.prop("string", "备注，可省略"),
                "onlySigned", ToolArgs.prop("boolean", "只给已签到的人加分，默认 true")
        ), List.of("credit"));
    }

    @Override
    public List<String> requiredPermissions() { return List.of("quality:activity_add"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    /* ============================================================ 读阶段：dry-run */

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        ActivityResolver.Resolution res =
                activityResolver.resolve(ToolArgs.lng(args, "activityId"), ToolArgs.str(args, "activityName"));
        if (res.failed()) return ToolResult.failed(res.message());
        Long activityId = res.activity().getId();
        Object rawCredit = args == null ? null : args.get("credit");
        if (rawCredit == null) {
            return ToolResult.failed("需要指定每人加分值，例如 0.5 分。");
        }
        BigDecimal credit;
        try {
            credit = new BigDecimal(String.valueOf(rawCredit).trim());
        } catch (Exception e) {
            return ToolResult.failed("加分值格式不正确：" + rawCredit);
        }
        if (credit.compareTo(BigDecimal.ZERO) <= 0) {
            return ToolResult.failed("加分值必须大于 0。");
        }

        String project = ToolArgs.str(args, "project", PROJECT_DEFAULT);
        String remark = ToolArgs.str(args, "remark");
        boolean onlySigned = args == null || !Boolean.FALSE.equals(args.get("onlySigned"));

        try {
            List<SigninRosterVO> roster = signinRosterService.listRoster(activityId, null);
            if (roster == null || roster.isEmpty()) {
                return ToolResult.failed("该活动还没有导入签到名单，无法批量加分。"
                        + "请先在签到管理页上传名单。");
            }

            // 已存在记录（用于预判跳过）——用同一活动已有的加分记录做去重预演
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (Map<String, Object> r : creditService.page(null, null, activityId, 1, 500).getRecords()) {
                Object n = r.get("personName");
                if (n != null) existing.add(String.valueOf(n).trim());
            }

            List<AgentActionPlan.PlanItem> items = new ArrayList<>();
            List<AgentActionPlan.SkippedItem> skipped = new ArrayList<>();
            List<Map<String, String>> peopleForCommit = new ArrayList<>();
            int signedCount = 0;

            // ---- 同一人可能在名册中出现多行（历史重复导入 / 简版与详版并存）----
            // 这类行对 isSigned() 的判定可能不一致（matches() 要求"所有非空字段相等"，
            // 详版行带签到记录里没有的字段 → 判为未签到）。不去重的后果是清单自相矛盾：
            // 同一个人既出现在"将加分"里、又出现在"将跳过（未签到）"里，用户无法判断。
            // 因此这里按「姓名」聚合，只要任一行已签到即视为已签到（取最宽口径，
            // 宁可多算进加分项、由用户核对，也不要漏掉本该加分的人）。
            Map<String, SigninRosterVO> uniqueByPerName = new LinkedHashMap<>();
            Map<String, Boolean> signedByName = new LinkedHashMap<>();
            int duplicateRows = 0;
            for (SigninRosterVO vo : roster) {
                Map<String, String> f = vo.getFields() == null ? Map.of() : vo.getFields();
                String nm = f.get("姓名");
                if (nm == null || nm.isBlank()) continue;
                nm = nm.trim();
                if (uniqueByPerName.containsKey(nm)) duplicateRows++;
                else uniqueByPerName.put(nm, vo);
                signedByName.merge(nm, vo.isSigned(), (a, b) -> a || b);
            }

            for (Map.Entry<String, SigninRosterVO> e : uniqueByPerName.entrySet()) {
                String name = e.getKey();
                SigninRosterVO vo = e.getValue();
                Map<String, String> f = vo.getFields() == null ? Map.of() : vo.getFields();
                String studentNo = f.get("学号");
                boolean signed = Boolean.TRUE.equals(signedByName.get(name));

                if (signed) signedCount++;

                // onlySigned 时未签到的跳过（并明确告知原因，而非静默丢弃）
                if (onlySigned && !signed) {
                    skipped.add(new AgentActionPlan.SkippedItem(name,
                            studentNo == null ? "未签到" : studentNo,
                            "未签到"));
                    continue;
                }
                // 重复加分预判（与服务侧去重口径一致：同名同活动）
                if (existing.contains(name)) {
                    skipped.add(new AgentActionPlan.SkippedItem(name,
                            studentNo == null ? "已有记录" : studentNo,
                            "本活动已加过分"));
                    continue;
                }

                items.add(new AgentActionPlan.PlanItem(vo.getId(), name,
                        (studentNo == null ? "" : studentNo + " · ") + credit.stripTrailingZeros().toPlainString() + " 分"));
                Map<String, String> p = new LinkedHashMap<>();
                p.put("personName", name);
                if (studentNo != null && !studentNo.isBlank()) p.put("studentNo", studentNo);
                peopleForCommit.add(p);
            }

            if (items.isEmpty()) {
                StringBuilder why = new StringBuilder("没有可加分的人员：");
                long unsignedSkip = skipped.stream().filter(s -> "未签到".equals(s.reason())).count();
                long dupSkip = skipped.stream().filter(s -> "本活动已加过分".equals(s.reason())).count();
                if (unsignedSkip > 0) why.append(unsignedSkip).append(" 人未签到；");
                if (dupSkip > 0) why.append(dupSkip).append(" 人已加过分；");
                if (unsignedSkip == 0 && dupSkip == 0) why.append("名单为空或缺少姓名。");
                return ToolResult.ok(why.toString());
            }

            List<String> detailLines = new ArrayList<>();
            detailLines.add("来源：活动 " + activityId + " 签到名单（共 " + roster.size() + " 行，唯一人员 "
                    + uniqueByPerName.size() + " 人，已签到 " + signedCount + " 人）");
            detailLines.add("加分：每人 " + credit.stripTrailingZeros().toPlainString() + " 分 · 项目「" + project + "」");
            if (remark != null && !remark.isBlank()) detailLines.add("备注：" + remark);
            if (duplicateRows > 0) {
                detailLines.add("提示：名册存在 " + duplicateRows + " 行重复记录，已按姓名合并去重。"
                        + "建议在签到管理页清理重复行。");
            }
            // 执行参数的结构化载体（前端隐藏展示，commit 阶段解析）
            detailLines.add(ARGS_MARK + buildArgsJson(activityId, credit, project, remark));

            String summary = "活动 " + activityId + " · " + project + "（" + items.size() + " 人 × "
                    + credit.stripTrailingZeros().toPlainString() + " 分）";

            AgentActionPlan plan = new AgentActionPlan(null, summary, items.size(), items, skipped,
                    detailLines, true, null);

            return ToolResult.plan(
                    "已核对完成：将为 " + items.size() + " 人加分，"
                            + (skipped.isEmpty() ? "无需跳过的人员。" : skipped.size() + " 人将跳过（原因已列在清单中）。")
                            + "请确认后执行。",
                    plan);
        } catch (Exception e) {
            return ToolResult.failed("核对失败：" + e.getMessage());
        }
    }

    /* ============================================================ 写阶段：用户确认后 */

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        if (plan == null || plan.items() == null || plan.items().isEmpty()) {
            return CommitResult.failed("清单为空，无需执行。");
        }

        // 执行参数从 detailLines 的结构化标记里还原（见类注释"关键设计点 2"）
        Map<String, Object> execArgs = readArgs(plan);
        if (execArgs == null) {
            return CommitResult.failed("无法还原执行参数，请重新发起加分。");
        }
        Long activityId = execArgs.get("activityId") == null ? null
                : Long.valueOf(String.valueOf(execArgs.get("activityId")));
        BigDecimal credit = execArgs.get("credit") == null ? null
                : new BigDecimal(String.valueOf(execArgs.get("credit")));
        String project = execArgs.get("project") == null ? PROJECT_DEFAULT
                : String.valueOf(execArgs.get("project"));
        String remark = execArgs.get("remark") == null ? null : String.valueOf(execArgs.get("remark"));

        if (activityId == null || credit == null) {
            return CommitResult.failed("清单中的活动 ID 或分值缺失，请重新发起。");
        }

        List<Map<String, String>> people = new ArrayList<>();
        for (AgentActionPlan.PlanItem item : plan.items()) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("personName", item.label());
            // sub 形如 "20230101 · 0.5 分"；取学号部分
            String sub = item.sub();
            if (sub != null && sub.contains("·")) {
                String no = sub.substring(0, sub.indexOf('·')).trim();
                if (!no.isEmpty()) p.put("studentNo", no);
            }
            people.add(p);
        }

        try {
            Map<String, Integer> res = creditService.batchAddFromActivity(
                    activityId, project, credit, remark, people, ctx.getUserId());
            int added = res.getOrDefault("added", 0);
            int skippedCount = res.getOrDefault("skipped", 0);

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "activity", "id", activityId,
                    "label", "活动 " + activityId + " 素拓台账",
                    "link", "/archive/credit?activityId=" + activityId));

            if (added == 0) {
                return new CommitResult("FAILED", 0, skippedCount,
                        "没有任何记录被写入（" + skippedCount + " 人因已存在或信息不全被跳过）。",
                        Map.of("skipped", skippedCount), refs);
            }
            String msg = "已为 " + added + " 人加分" + (skippedCount > 0 ? "，" + skippedCount + " 人被跳过" : "") + "。";
            if (skippedCount > 0) {
                return new CommitResult("PARTIAL", added, skippedCount, msg,
                        Map.of("added", added, "skipped", skippedCount), refs);
            }
            return CommitResult.success(added, msg);
        } catch (Exception e) {
            return CommitResult.failed("加分执行失败：" + e.getMessage());
        }
    }

    /* ============================================================ 执行参数载体 */

    private String buildArgsJson(Long activityId, BigDecimal credit, String project, String remark) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activityId", activityId);
        m.put("credit", credit.toPlainString());
        m.put("project", project);
        if (remark != null) m.put("remark", remark);
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArgs(AgentActionPlan plan) {
        if (plan.detailLines() == null) return null;
        for (String line : plan.detailLines()) {
            if (line != null && line.startsWith(ARGS_MARK)) {
                try {
                    return mapper.readValue(line.substring(ARGS_MARK.length()), Map.class);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
