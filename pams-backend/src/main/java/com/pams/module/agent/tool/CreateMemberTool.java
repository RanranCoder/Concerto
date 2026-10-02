package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.*;
import com.pams.module.member.dto.MemberRequest;
import com.pams.module.member.service.MemberService;
import com.pams.module.member.service.MemberSessionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：新增成员（业务设计方案 §3.5）。
 *
 * <p>回答"把新同学加进来""新增一个干事"。落库走 {@code MemberService.create}，
 * 由服务侧做学号唯一性校验（同届别学号不可重复）。
 *
 * <p>数据级 {@code SENSITIVE}：成员信息含姓名、学号、手机号。
 * 虽然 {@code execute} 只回显用户自己提供的内容（不额外查库），
 * 但仍标记为 sensitive —— 因为用户输入里可能带手机号，
 * 出网前要过 PolicyGuard 遮罩（D4）。
 *
 * <p>权限码 {@code member:manage}；可撤回（成员可删除重加）。
 */
@Component
public class CreateMemberTool implements WriteAgentTool {

    private static final String ARGS_MARK = "__args__:";

    private final MemberService memberService;
    private final MemberSessionService sessionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CreateMemberTool(MemberService memberService, MemberSessionService sessionService) {
        this.memberService = memberService;
        this.sessionService = sessionService;
    }

    @Override
    public String name() { return "createMember"; }

    @Override
    public String label() { return "新增成员"; }

    @Override
    public String runningLabel() { return "正在准备成员信息…"; }

    @Override
    public String description() {
        return "向指定届别新增一名学生/干事成员，填写姓名、学号、部门、职务等。"
                + "适用于'新增一个干事''把新同学加进名单'等指令。"
                + "不指定届别时使用当前届别。会先生成待确认信息供核对。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "name", ToolArgs.prop("string", "姓名，必填"),
                "sessionId", ToolArgs.prop("integer", "届别 ID，可省略（默认当前届别）"),
                "deptId", ToolArgs.prop("integer", "部门 ID，可省略"),
                "position", ToolArgs.prop("string", "职务，可省略"),
                "studentNo", ToolArgs.prop("string", "学号，可省略"),
                "className", ToolArgs.prop("string", "班级，可省略"),
                "phone", ToolArgs.prop("string", "手机号，可省略"),
                "gender", ToolArgs.prop("string", "性别 M/F，可省略"),
                "remark", ToolArgs.prop("string", "备注，可省略")
        ), List.of("name"));
    }

    @Override
    public List<String> requiredPermissions() { return List.of("member:manage"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.SENSITIVE; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String name = ToolArgs.str(args, "name");
        if (name == null || name.isBlank()) return ToolResult.failed("成员姓名不能为空。");

        Long sessionId = ToolArgs.lng(args, "sessionId");
        Long deptId = ToolArgs.lng(args, "deptId");
        String position = ToolArgs.str(args, "position");
        String studentNo = ToolArgs.str(args, "studentNo");
        String className = ToolArgs.str(args, "className");
        String phone = ToolArgs.str(args, "phone");
        String gender = ToolArgs.str(args, "gender");
        String remark = ToolArgs.str(args, "remark");

        // 未指定届别 → 取当前届别
        String sessionLabel = null;
        if (sessionId == null) {
            try {
                Long current = currentSessionId();
                if (current != null) {
                    sessionId = current;
                    sessionLabel = "当前届别（#" + current + "）";
                }
            } catch (Exception ignored) {
                // 取不到就要求用户指定
            }
            if (sessionId == null) {
                return ToolResult.failed("无法确定要加入的届别，请说明是第几届（或先在成员管理里设置当前届别）。");
            }
        } else {
            sessionLabel = "届别 #" + sessionId;
        }

        List<AgentActionPlan.PlanItem> items = new ArrayList<>();
        items.add(new AgentActionPlan.PlanItem(null, "姓名", name));
        items.add(new AgentActionPlan.PlanItem(null, "届别", sessionLabel));
        if (deptId != null) items.add(new AgentActionPlan.PlanItem(null, "部门", "#" + deptId));
        if (position != null && !position.isBlank()) items.add(new AgentActionPlan.PlanItem(null, "职务", position));
        if (studentNo != null && !studentNo.isBlank()) items.add(new AgentActionPlan.PlanItem(null, "学号", studentNo));
        if (className != null && !className.isBlank()) items.add(new AgentActionPlan.PlanItem(null, "班级", className));
        if (phone != null && !phone.isBlank()) items.add(new AgentActionPlan.PlanItem(null, "手机号", phone));

        List<AgentActionPlan.SkippedItem> skipped = new ArrayList<>();
        if (studentNo == null || studentNo.isBlank()) {
            skipped.add(new AgentActionPlan.SkippedItem("学号", "未提供",
                    "未提供学号，后续无法做名单匹配与开号"));
        }

        List<String> detailLines = new ArrayList<>();
        detailLines.add("届别：" + sessionLabel);
        detailLines.add("说明：同届别下学号不可重复，若学号已存在将创建失败。");
        detailLines.add("该操作可撤回：删除该成员即可。");

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("name", name);
        exec.put("sessionId", sessionId);
        if (deptId != null) exec.put("deptId", deptId);
        for (Map.Entry<String, String> e : Map.of(
                "position", position == null ? "" : position,
                "studentNo", studentNo == null ? "" : studentNo,
                "className", className == null ? "" : className,
                "phone", phone == null ? "" : phone,
                "gender", gender == null ? "" : gender,
                "remark", remark == null ? "" : remark).entrySet()) {
            if (!e.getValue().isBlank()) exec.put(e.getKey(), e.getValue());
        }
        detailLines.add(ARGS_MARK + writeJson(exec));

        AgentActionPlan plan = new AgentActionPlan(null, "新增成员：" + name, items.size(), items,
                skipped, detailLines, true, null);

        return ToolResult.plan("已整理好成员信息，请核对后确认添加。", plan);
    }

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原成员信息，请重新发起。");

        Long sessionId = lng(exec.get("sessionId"));
        if (sessionId == null) return CommitResult.failed("届别缺失，请重新发起。");

        try {
            MemberRequest req = new MemberRequest(
                    sessionId,
                    lng(exec.get("deptId")),
                    str(exec.get("position")),
                    str(exec.get("name")),
                    str(exec.get("gender")),
                    str(exec.get("studentNo")),
                    str(exec.get("className")),
                    str(exec.get("phone")),
                    null,                       // politicalStatus：由后续流程维护
                    "ACTIVE",                   // status
                    str(exec.get("remark")));

            Long id = memberService.create(req, ctx.getUserId());

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "member", "id", id, "label", str(exec.get("name")),
                    "link", "/member?id=" + id));

            return new CommitResult("SUCCESS", 1, 0,
                    "成员「" + str(exec.get("name")) + "」已添加。",
                    ToolArgs.map("memberId", id), refs);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            if (msg.contains("学号已存在")) {
                return CommitResult.failed("该届别下学号已存在，请核对后再试。");
            }
            return CommitResult.failed("成员添加失败：" + msg);
        }
    }

    /** 读当前届别（取列表首个；取不到返回 null 而非抛异常，由上层给出可读提示） */
    private Long currentSessionId() {
        try {
            List<com.pams.module.member.dto.MemberSessionVO> sessions = sessionService.list();
            if (sessions != null && !sessions.isEmpty()) {
                return sessions.get(0).id();
            }
        } catch (Exception ignored) {
            // 交由调用方处理
        }
        return null;
    }

    private String writeJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArgs(AgentActionPlan plan) {
        if (plan == null || plan.detailLines() == null) return null;
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

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Long lng(Object o) {
        if (o == null) return null;
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
