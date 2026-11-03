package com.pams.module.agent.core;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolDescriptorTest {
    private AgentTool tool(String name, boolean write) {
        var tool = mock(AgentTool.class, CALLS_REAL_METHODS);
        when(tool.name()).thenReturn(name);
        when(tool.writeOperation()).thenReturn(write);
        when(tool.sensitivity()).thenReturn(Sensitivity.NORMAL);
        when(tool.requiredPermissions()).thenReturn(List.of("activity:view"));
        when(tool.parametersSchema()).thenReturn(Map.of("type", "object"));
        return tool;
    }

    @Test void diagnosticsIntersectPermissionModeAndExplicitScope() {
        var read = tool("read", false);
        var write = tool("write", true);
        var registry = new ToolRegistry(List.of(write, read));
        var ctx = new ToolContext(null, List.of(new SimpleGrantedAuthority("activity:view")), null, null, "readonly");
        assertThat(registry.effectiveDescriptors(ctx, Set.of("read", "write")))
                .extracting(ToolDescriptor::name).containsExactly("read");
        assertThat(registry.effectiveDescriptors(ctx, Set.of())).isEmpty();
        assertThat(registry.effectiveDescriptors(new ToolContext(null, List.of(), null, null), Set.of("read"))).isEmpty();
        assertThat(registry.toolSchemas(ctx).getFirst()).containsKeys("name", "write", "descriptor");
    }

    @Test void duplicateNamesFailInsteadOfSilentlyReplacingAnImplementation() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("same", false), tool("same", true))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void legacyMetadataDoesNotPromiseFormCoverageOrVerifiedIdempotency() {
        var descriptor = tool("write", true).descriptor();
        assertThat(descriptor.supportsForm()).isFalse();
        assertThat(descriptor.operationKind()).isEqualTo("PREPARE");
        assertThat(descriptor.completenessPolicy()).isEqualTo("NOT_DECLARED");
        assertThat(descriptor.idempotencyPolicy()).contains("NOT_BUSINESS_VERIFIED");
    }
}
