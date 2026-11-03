package com.pams.module.agent.tool;
import com.pams.module.agent.service.PlanRevisionService;
import com.pams.module.agent.core.*;
import com.pams.common.BizException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparePlanRevisionToolTest {
    @Test void unexpectedCommitFailureIsUnknownRatherThanClaimedNotExecuted() throws Exception {
        var revisions=mock(PlanRevisionService.class);var tool=new PreparePlanRevisionTool(revisions);
        var ctx=mock(ToolContext.class);var action=AgentActionPlan.empty();
        when(revisions.commit(ctx,action)).thenThrow(new IllegalStateException("connection lost"));
        assertThat(tool.commit(ctx,action).status()).isEqualTo("RESULT_UNKNOWN");
        doThrow(new BizException(409,"STALE_BASELINE")).when(revisions).commit(ctx,action);
        assertThat(tool.commit(ctx,action).status()).isEqualTo("FAILED");
    }
}
