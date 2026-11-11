package com.pams.module.agent.tool;

import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Bounded, built-in workflow guidance loaded only when the model needs it. */
@Component
public class LoadBusinessWorkflowTool implements AgentTool {
    private static final Map<String,String> GUIDES = Map.of(
            "plan-document", """
                    先确认关联活动和当前可见版本。从活动详情获取时间、地点和组织者，区分已核实事实与建议。
                    起草时按用途组织标题、段落、列表和预算表。核对预算汇总、责任分工、时间先后和未确认事项。
                    用户委托保存新稿时，以 draftPlanSections 的 saveDraft 保存 Word，核对回执。
                    局部修订时先读当前 DOCX，再对已读块使用 preparePlanRevision 的 edits。逐项检查目标、原文和范围。
                    """,
            "scheduling", """
                    先查询活动、人员和已有安排。核对时间、角色、冲突和未分配人员。
                    发现冲突时优先查可用人员及时间，只有缺少必要决策才一次性询问。
                    排班预览与发布分别处理，输出覆盖范围和未解决冲突。
                    """,
            "announcement", """
                    从可见活动和资料核对名称、时间、地点及受众。先准备内容，检查未知信息和链接。
                    对外发送或发布前提供具体版本和受众。按工具回执分别说明准备、提交和发布状态。
                    """);

    public String name() { return "loadBusinessWorkflow"; }
    public String label() { return "读取业务工作方法"; }
    public String runningLabel() { return "正在读取相关业务步骤…"; }
    public String description() { return "按需读取内置工作方法：plan-document（Word策划书）、scheduling（排班）、announcement（公告和通知）。仅使用当前已授权工具。"; }
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }
    public Map<String,Object> parametersSchema() {
        return ToolArgs.schema("object", Map.of("workflow", Map.of("type", "string", "enum",
                List.of("plan-document", "scheduling", "announcement"))), List.of("workflow"));
    }
    public ToolResult execute(ToolContext ctx, Map<String,Object> args) {
        String workflow = ToolArgs.str(args, "workflow", "");
        String guide = GUIDES.get(workflow);
        return guide == null ? ToolResult.failed("工作方法不存在") : ToolResult.ok(guide);
    }
}
