package com.pams.module.agent.tool;

import com.pams.common.PageResult;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.party.service.PartyMemberService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 读类工具：党员发展进度（业务设计方案 §3.5 / D11 第三类风险的<b>典型样本</b>）。
 *
 * <p><b>本工具是 D11「第一类透明变更」的教科书例子</b>：
 * {@code PartyMemberService.page(keyword, stage, page, size, staff)} 的
 * 第 5 个参数 {@code staff} 由 <b>Controller 传入</b>，决定是否脱敏。
 * 工具直调 Service 时必须显式传 {@code ctx.isStaff()}——
 * 漏传（或写死 true）会导致<b>普通干事看到完整名册</b>，而且<b>不报错</b>。
 *
 * <p>因此这里<b>不自行判断角色</b>，一律用 {@link ToolContext#isStaff()}，
 * 让口径与 Controller 完全一致（ToolConventions 统一定义）。
 *
 * <p>数据级 {@code SENSITIVE}：含姓名、联系方式 → 出网前经 PolicyGuard 遮罩，
 * 会话中只存摘要（D4）。
 *
 * <p><b>2026-09-14 修复（审计 A6）</b>：此前 {@code staff=true} 时摘要里仍原样列出
 * 前 5 个姓名，却在同一句话里标注"敏感字段已脱敏"——<b>这是一句假话</b>。
 * {@code PartyMemberService} 只遮了证件/地址/电话（它知道那些字段是敏感的），
 * 而 summary 里的姓名是"服务端拼出来的文本"，不在其职责范围。
 * 现在 staff 情形下摘要姓名一并过 {@link PolicyGuard#maskName}。
 *
 * <p>为什么不用 {@link PolicyGuard#egress} 顺便遮姓名：中文姓名无法用正则可靠识别，
 * 在闸口上做会改坏正常文本。所以分工是——<b>闸口管模式（证件号/手机号），
 * 调用点管语义（谁是姓名）</b>。
 */
@Component
public class QueryPartyMemberProgressTool implements AgentTool {

    private final PartyMemberService partyMemberService;
    private final PolicyGuard policyGuard;

    public QueryPartyMemberProgressTool(PartyMemberService partyMemberService,
                                        PolicyGuard policyGuard) {
        this.partyMemberService = partyMemberService;
        this.policyGuard = policyGuard;
    }

    @Override
    public String name() { return "queryPartyMemberProgress"; }

    @Override
    public String label() { return "党员发展进度"; }

    @Override
    public String runningLabel() { return "正在查询党员发展进度…"; }

    @Override
    public String description() {
        return "查询党员/入党积极分子的发展进度与所处阶段。用于回答'XX 发展到哪一步了'"
                + "'有哪些人在积极分子阶段''预备党员有哪些'等问题。"
                + "涉及个人信息的字段会自动脱敏。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "keyword", ToolArgs.prop("string", "姓名关键词，可省略"),
                "stage", ToolArgs.prop("string", "发展阶段，如 积极分子 / 发展对象 / 预备党员，可省略"),
                "limit", ToolArgs.prop("integer", "返回条数上限，默认 10")
        ), List.of());
    }

    /**
     * <b>2026-09-14 改为角色口径（权限审计 A9）</b>：原来是
     * {@code requiredPermissions() = ["party:view"]}，但该权限码在 V7 种子里**授予了 STAFF**
     * ——于是出现了一条确凿的越权链路：
     *
     * <pre>
     * 前端  /party/* 路由用 RequireRole(LEADER_ROLES) 守卫 → 干事的党务台账被 /403 拦下、菜单也隐藏
     * 后端  PartyMemberController.page 当时无 @PreAuthorize（已修）
     * 灵思  本工具靠 party:view 判定 → STAFF 持有该码 ⇒ 工具对干事可见
     *       ⇒ 干事问一句"有哪些预备党员"，就能拿到 姓名 + 阶段 + 所属 + 申请时间
     * </pre>
     *
     * <p>根因是**同一资源混用两套口径**（前端角色、工具权限码），而且冲突方向恰好是"工具更松"。
     * 现在统一为**角色口径**（与 {@code RequireRole(LEADER_ROLES)}、
     * {@code PartyMemberController} 的 {@code @PreAuthorize(RoleExprs.LEADER)} 三处一致）。
     *
     * <p>为什么不用"把 party:view 从 STAFF 摘掉"来修：那治的是症状。用权限码表达
     * "部长及以上"这种**角色级别**语义本身就是错的——它会让权限管理页产生新的失败模式
     * （管理员为开放某功能勾了 party:view，顺带把干事放进了党务台账）。
     */
    @Override
    public boolean requiresLeader() { return true; }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.SENSITIVE; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String keyword = ToolArgs.str(args, "keyword");
        String stage = ToolArgs.str(args, "stage");
        int limit = Math.min(Math.max(ToolArgs.intVal(args, "limit", 10), 1), 30);

        // ⚠️ 关键：staff 必须从 ctx 推导，绝不能写死 —— 见类注释
        boolean staff = ctx.isStaff();

        try {
            PageResult<Map<String, Object>> page =
                    partyMemberService.page(keyword, stage, 1, limit, staff);
            List<Map<String, Object>> records = page.getRecords() == null ? List.of() : page.getRecords();

            if (records.isEmpty()) {
                return ToolResult.ok("没有找到符合条件的发展对象记录。");
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> refs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> m : records) {
                Object name = m.get("name") != null ? m.get("name") : m.get("realName");
                if (name != null) names.add(String.valueOf(name));
                rows.add(ToolArgs.row(
                        "id", m.get("id"),
                        "name", name,
                        "stage", m.get("stage"),
                        "deptName", m.get("deptName"),
                        "applyDate", m.get("applyDate")
                ));
                refs.add(ToolArgs.map("type", "partyMember", "id", m.get("id"),
                        "label", name, "link", "/party/member?id=" + m.get("id")));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("count", page.getTotal());
            payload.put("returned", records.size());
            payload.put("masked", staff);
            payload.put("table", ToolArgs.table(
                    new String[]{"姓名", "阶段", "所属", "申请时间"}, rows));
            payload.put("page", "party");

            // 摘要里的姓名：staff 情形必须一并遮罩，否则"敏感字段已脱敏"是假话（审计 A6）
            List<String> displayNames = staff
                    ? names.stream().map(policyGuard::maskName).collect(Collectors.toList())
                    : names;

            String summary = "共查到 " + page.getTotal() + " 条发展记录，涉及 "
                    + ToolArgs.listSummary(displayNames, 5) + "。"
                    + (staff ? "（普通权限，个人证件类字段与姓名已脱敏）" : "");
            return ToolResult.ok(summary, payload, refs);
        } catch (Exception e) {
            return ToolResult.failed("党员发展信息读取失败：" + e.getMessage());
        }
    }
}
