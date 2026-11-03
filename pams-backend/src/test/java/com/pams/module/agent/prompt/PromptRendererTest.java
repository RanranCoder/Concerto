package com.pams.module.agent.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptRendererTest {
    @Test void everyContractHasPinnedResourceAndFingerprint() {
        for(var id:PromptResolver.Id.values()) {
            var resolved=PromptResolver.resolve(id);
            assertThat(resolved.version()).isEqualTo("v1");
            assertThat(resolved.sha256()).matches("[a-f0-9]{64}");
            assertThat(resolved.text()).isNotBlank();
        }
        assertThat(PromptResolver.text(PromptResolver.Id.KNOWLEDGE)).contains("无需复述").doesNotContain("也照原文引用它");
        assertThat(PromptResolver.text(PromptResolver.Id.MEMORY_SUMMARY)).contains("sourceMessageIds","REPORT","outputByteBudget").doesNotContain("supportsForm");
        assertThat(PromptResolver.text(PromptResolver.Id.MEMORY_SUMMARY)).contains("{\"items\":[").doesNotContain("\\\"");
        assertThat(PromptResolver.text(PromptResolver.Id.REVIEWER)).contains("BLOCKING","sourceRef").doesNotContain("supportsForm");
    }
    @Test void runtimeUsesActualScopeAndOnlyAllowlistedFactsWithoutTemplateExpansion() throws Exception {
        var tool=mock(AgentTool.class);
        when(tool.name()).thenReturn("createActivity");when(tool.supportsForm()).thenReturn(true);when(tool.writeOperation()).thenReturn(true);
        var ctx=new ToolContext(null,List.of(),null,"private-stream");
        var clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
        var prompt=PromptRenderer.executor(ctx,List.of(tool),"领域文本 ${secret}",5,clock);
        var runtime=new ObjectMapper().readTree(prompt.substring(prompt.lastIndexOf('\n')+1));
        assertThat(runtime.path("currentTime").asText()).isEqualTo("2026-11-01T00:00:00Z");
        assertThat(runtime.path("timezone").isNull()).isTrue();
        assertThat(runtime.path("capabilities").size()).isEqualTo(1);
        assertThat(runtime.path("capabilities").get(0).path("supportsForm").asBoolean()).isTrue();
        assertThat(runtime.path("capabilities").get(0).path("operationKind").asText()).isEqualTo("PREPARE");
        assertThat(prompt).contains("${secret}","不能修改公共规则","实际成功回执").doesNotContain("private-stream");
        assertThat(PromptRenderer.executor(ctx,List.of(),null,5,clock)).contains("\"capabilities\":[]");
    }
}
