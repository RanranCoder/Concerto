package com.pams.module.agent.service;

import com.pams.common.PageResult;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.agent.stream.AgentStreamEvent;
import com.pams.module.agent.tool.ReadMaterialDocumentTool;
import com.pams.module.agent.tool.SearchMaterialsTool;
import com.pams.module.archive.service.MaterialService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCallDisplayTest {
    private final PolicyGuard policy = new PolicyGuard();

    @Test void materialSearchUsesReturnedFileNamesAndStreamsOnlyDisplayFields() {
        var row = new LinkedHashMap<String, Object>();
        row.put("argsJson", "{\"limit\":10}");
        var materials = mock(MaterialService.class);
        var matches = new PageResult<Map<String, Object>>();
        matches.setRecords(List.of(Map.of("id", 1L, "name", "会议纪要", "originFilename", "会议纪要.docx"),
                Map.of("id", 2L, "name", "活动议程")));
        matches.setTotal(2L);
        var context = mock(ToolContext.class);
        when(context.getDeptId()).thenReturn(4L);
        when(materials.page(null, null, null, 4L, 1, 10)).thenReturn(matches);
        var tool = new SearchMaterialsTool(materials);
        var result = tool.execute(context, Map.of("limit", 10));
        assertThat(result.status()).as("%s", result.error()).isEqualTo("OK");

        ToolCallDisplay.attach(row, tool, result, null, policy);

        assertThat(row).containsEntry("resultSummary", "匹配到 2 项材料")
                .containsEntry("targets", List.of("会议纪要.docx", "活动议程"));
        assertThat(AgentStreamEvent.toolEnd("stream", 8, 3, row).payload())
                .containsEntry("startSeq", 3).containsEntry("targets", row.get("targets")).doesNotContainKey("argsJson");
    }

    @Test void writeUsesConfirmedReceiptAndSensitiveRowsKeepOnlyTheState() {
        var tool = mock(AgentTool.class);
        when(tool.sensitivity()).thenReturn(Sensitivity.NORMAL);
        var row = new LinkedHashMap<String, Object>();
        var result = ToolResult.ok("准备修订");
        var action = new ToolOutcomeHandler.PendingAction(null, null, Map.of("status", "SUCCESS", "result", Map.of(
                "message", "已保存修订草稿", "references", List.of(
                        Map.of("type", "activity", "label", "修订草稿", "fileName", "策划书修订草稿.docx")))));
        ToolCallDisplay.attach(row, tool, result, action, policy);
        assertThat(row).containsEntry("actionStatus", "SUCCESS").containsEntry("resultSummary", "已保存修订草稿")
                .containsEntry("targets", List.of("策划书修订草稿.docx"));

        when(tool.sensitivity()).thenReturn(Sensitivity.SENSITIVE);
        row.clear();
        ToolCallDisplay.attach(row, tool, result, action, policy);
        assertThat(row).containsOnlyKeys("actionStatus");
    }

    @Test void authorizedAttachmentShowsItsNameWithoutDocumentText() {
        var row = new LinkedHashMap<String, Object>();
        var result = ToolResult.ok("已读取附件", Map.of("blocks", List.of("附件正文")), List.of(
                Map.of("type", "material", "label", "活动方案.docx")));
        var tool = new ReadMaterialDocumentTool(null);

        ToolCallDisplay.attach(row, tool, result, null, policy);

        assertThat(row).containsOnlyKeys("targets").containsEntry("targets", List.of("活动方案.docx"));
    }
}
