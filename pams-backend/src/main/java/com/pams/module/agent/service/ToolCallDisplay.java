package com.pams.module.agent.service;

import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded, user-facing details from the tool result and any confirmed write receipt. */
public final class ToolCallDisplay {
    private static final Set<String> DOCUMENT_TYPES = Set.of("material", "file", "plan", "article");

    private ToolCallDisplay() {}

    public static void attach(Map<String, Object> row, AgentTool tool, ToolResult result,
                              ToolOutcomeHandler.PendingAction action, PolicyGuard policy) {
        Map<String, Object> receipt = action == null ? null : action.receipt();
        if (receipt != null && receipt.get("status") instanceof String status) {
            row.put("actionStatus", status);
        } else if (action != null && action.plan() != null) {
            row.put("actionStatus", "PENDING_CONFIRM");
            row.put("actionTaskId", action.plan().planId());
        } else if (action != null && action.form() != null) {
            row.put("actionTaskId", action.form().get("taskId"));
        }

        boolean normal = tool.sensitivity() == Sensitivity.NORMAL;
        // This tool authorizes the attachment through FileService before returning its display name.
        boolean attachmentName = tool.sensitivity() == Sensitivity.SENSITIVE && "readMaterialDocument".equals(tool.name());
        if (!result.isOk() || (!normal && !attachmentName)) return;
        String summary = null;
        List<?> references = result.references();
        if (normal && receipt != null && receipt.get("result") instanceof Map<?, ?> details) {
            summary = bounded(details.get("message"), policy, 160);
            references = details.get("references") instanceof List<?> refs ? refs : List.of();
        } else if (normal && receipt == null) {
            summary = switch (tool.name()) {
                case "queryMyPendingWork", "queryActivityOverview" -> bounded(result.summary(), policy, 160);
                case "searchMaterials" -> result.payload().get("count") instanceof Number count
                        ? "匹配到 " + count + " 项材料" : "没有匹配的材料";
                default -> null;
            };
        }

        List<String> targets = references.stream()
                .filter(Map.class::isInstance)
                .map(value -> (Map<?, ?>) value)
                .filter(ref -> receipt != null || DOCUMENT_TYPES.contains(Objects.toString(ref.get("type"), "")))
                .map(ref -> bounded(ref.get("fileName") instanceof String name && !name.isBlank()
                        ? name : ref.get("label"), policy, 180))
                .filter(Objects::nonNull).distinct().limit(30).toList();
        if (summary != null) row.put("resultSummary", summary);
        if (!targets.isEmpty()) row.put("targets", targets);
    }

    private static String bounded(Object value, PolicyGuard policy, int maxLength) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        String clean = policy.mask(text).strip().replaceAll("\\s+", " ");
        int end = clean.offsetByCodePoints(0, Math.min(clean.codePointCount(0, clean.length()), maxLength));
        return end == clean.length() ? clean : clean.substring(0, end) + "…";
    }
}
