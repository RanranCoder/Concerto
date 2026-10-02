package com.pams.module.agent.tool;

import com.pams.module.activity.service.SigninRosterService;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：签到统计（业务设计方案 §3.5）。
 *
 * <p>回答"签到率多少""还有谁没签"。走 {@code SigninRosterService.summary(activityId)}
 * ——既有的应签/已签/未签统计。
 *
 * <p>数据级 normal：只给统计数字，<b>不列未签人员姓名</b>（列出姓名会让工具结果里
 * 出现大量人名，既无必要也增加出网面）。
 */
@Component
public class QueryRosterSigninStatsTool implements AgentTool {

    private final SigninRosterService signinRosterService;
    private final ActivityResolver activityResolver;

    public QueryRosterSigninStatsTool(SigninRosterService signinRosterService,
                                      ActivityResolver activityResolver) {
        this.signinRosterService = signinRosterService;
        this.activityResolver = activityResolver;
    }

    @Override
    public String name() { return "queryRosterSigninStats"; }

    @Override
    public String label() { return "签到统计"; }

    @Override
    public String runningLabel() { return "正在统计签到情况…"; }

    @Override
    public String description() {
        return "查询某个活动的签到统计：应签人数、已签人数、未签人数、签到率。"
                + "用于回答'XX 活动的签到率''签到情况怎么样'等问题。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "活动 ID，可省略；与 activityName 二选一"),
                "activityName", ToolArgs.prop("string", ActivityResolver.NAME_DESC),
                "includeUnsigned", ToolArgs.prop("boolean", "是否列出未签人员姓名，默认 false")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("checkin:view"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        ActivityResolver.Resolution res =
                activityResolver.resolve(ToolArgs.lng(args, "activityId"), ToolArgs.str(args, "activityName"));
        if (res.failed()) return ToolResult.failed(res.message());
        Long activityId = res.activity().getId();
        boolean includeUnsigned = ToolArgs.bool(args, "includeUnsigned");

        try {
            com.pams.module.activity.dto.SigninSummaryVO summary =
                    signinRosterService.summary(activityId);
            if (summary == null) {
                return ToolResult.ok("该活动还没有导入签到名单，暂无签到数据。");
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("activityId", activityId);
            payload.put("expected", summary.getExpected());
            payload.put("signed", summary.getSigned());
            payload.put("unsigned", summary.getUnsigned());
            if (summary.getExpected() > 0) {
                payload.put("rate", Math.round(summary.getSigned() * 1000.0 / summary.getExpected()) / 10.0);
            }
            payload.put("page", "checkin");

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "activity", "id", activityId,
                    "label", "活动 " + activityId + " 签到", "link", "/activity/" + activityId + "/signin"));

            StringBuilder sb = new StringBuilder();
            sb.append("应签 ").append(summary.getExpected()).append(" 人");
            sb.append("，已签 ").append(summary.getSigned()).append(" 人");
            sb.append("，未签 ").append(summary.getUnsigned()).append(" 人");
            if (summary.getExpected() > 0) {
                sb.append("，签到率 ")
                  .append(String.format("%.1f", summary.getSigned() * 100.0 / summary.getExpected()))
                  .append("%");
            }
            sb.append("。");
            if (summary.getUnsigned() > 0 && !includeUnsigned) {
                sb.append("如需查看未签名单，请到签到管理页面查看。");
            }
            return ToolResult.ok(sb.toString(), payload, refs);
        } catch (Exception e) {
            return ToolResult.failed("签到统计读取失败：" + e.getMessage());
        }
    }
}
