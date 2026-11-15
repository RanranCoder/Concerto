package com.pams.module.agent.tool;

import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.common.OrgContext;
import com.pams.module.volunteer.VolunteerService;
import com.pams.module.youth.YouthService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

public final class DomainPackReadTools {
    private DomainPackReadTools() {}

    private abstract static class Summary implements AgentTool {
        @Override public Sensitivity sensitivity() { return Sensitivity.NORMAL; }
        @Override public String runningLabel() { return "正在" + label() + "…"; }
        @Override public Map<String, Object> parametersSchema() { return ToolArgs.schema("object", Map.of(), List.of()); }

        protected boolean currentOrganization(ToolContext context) {
            return context.getLoginUser() != null && java.util.Objects.equals(context.getLoginUser().getOrgId(), OrgContext.get());
        }
    }

    @Component
    public static class Youth extends Summary {
        private final YouthService service;
        public Youth(YouthService service) { this.service = service; }
        @Override public String name() { return "queryYouthWorkSummary"; }
        @Override public String label() { return "查询团务待办"; }
        @Override public String description() { return "统计当前组织待审核的评议和推优记录，以及待归档的组织生活场次。仅返回数量。"; }
        @Override public List<String> requiredPermissions() { return List.of("youth:view"); }
        @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
            if (!currentOrganization(context)) return ToolResult.denied("组织上下文不匹配");
            var summary = service.overview();
            var data = Map.of("pendingReviews", summary.get("pendingReviews"), "pendingMinutes", summary.get("pendingMinutes"));
            return ToolResult.ok("评议与推优待审核 %s 条，组织生活待归档 %s 场。".formatted(data.get("pendingReviews"), data.get("pendingMinutes")),
                    data, List.of(Map.of("type", "workspace", "label", "团务工作", "link", "/youth")));
        }
    }

    @Component
    public static class Volunteer extends Summary {
        private final VolunteerService service;
        public Volunteer(VolunteerService service) { this.service = service; }
        @Override public String name() { return "queryVolunteerWorkSummary"; }
        @Override public String label() { return "查询志愿服务汇总"; }
        @Override public String description() { return "统计当前组织的志愿活动数、报名人次、待审核记录及已确认的服务分钟数。仅返回汇总。"; }
        @Override public List<String> requiredPermissions() { return List.of("volunteer:view"); }
        @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
            if (!currentOrganization(context)) return ToolResult.denied("组织上下文不匹配");
            var data = service.overview();
            return ToolResult.ok("志愿活动 %s 场，报名 %s 人次，待审核 %s 条，已确认服务时长 %s 分钟。"
                            .formatted(data.get("activities"), data.get("registrations"), data.get("pending"), data.get("approvedMinutes")),
                    data, List.of(Map.of("type", "workspace", "label", "志愿服务", "link", "/volunteer")));
        }
    }
}
