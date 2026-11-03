package com.pams.module.agent.core;

import java.util.List;
import java.util.Map;

/** T0 compatibility descriptor. PREPARE is deliberately coarse until each action is audited. */
public record ToolDescriptor(
        String name, String schemaVersion, String operationKind,
        List<String> requiredPermissions, String permissionMode, boolean requiresLeader,
        Sensitivity sensitivity, boolean supportsForm, Map<String, Object> parametersSchema,
        String completenessPolicy, String idempotencyPolicy) {
    public static ToolDescriptor from(AgentTool tool) {
        return new ToolDescriptor(tool.name(), "1", tool.writeOperation() ? "PREPARE" : "READ",
                List.copyOf(tool.requiredPermissions()), tool.anyPermission() ? "ANY" : "ALL",
                tool.requiresLeader(), tool.sensitivity(), tool.supportsForm(),
                tool.parametersSchema(), "NOT_DECLARED",
                tool.writeOperation() ? "LEGACY_CONFIRM_GATE_NOT_BUSINESS_VERIFIED" : "NOT_APPLICABLE");
    }
}
