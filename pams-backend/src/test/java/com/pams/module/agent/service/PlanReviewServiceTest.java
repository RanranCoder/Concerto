package com.pams.module.agent.service;

import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentWorkItem;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.orchestration.WorkItemState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanReviewServiceTest {
    @Test void emptyContentOrAnInventedQuoteCannotProduceACheckedReview() throws Exception {
        var service=new PlanReviewService(mock(WorkItemState.class),mock(AgentLlmCaller.class),new PolicyGuard());
        assertThatThrownBy(()->service.parse("{}","正文","任务","")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.parse("{\"issues\":[{\"severity\":\"BLOCKING\",\"quote\":\"不存在的句子\",\"reason\":\"错误\",\"sourceRef\":\"task\"}]}","正文","任务","")).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.parse("{\"issues\":[]}","正文","任务","").status()).isEqualTo("CHECKED");
    }
    @Test void stubOrMalformedReviewIsUnverifiedInsteadOfPass() {
        var state=mock(WorkItemState.class);var caller=mock(AgentLlmCaller.class);var tool=mock(AgentTool.class);
        when(tool.sensitivity()).thenReturn(Sensitivity.NORMAL);
        var node=new AgentWorkItem();node.setId(3L);node.setInstruction("任务");node.setDependenciesJson("[]");
        when(state.nodes(1L)).thenReturn(List.of(node));when(state.dependencies(node)).thenReturn(List.of());
        var service=new PlanReviewService(state,caller,new PolicyGuard());
        var ctx=new ToolContext(null,List.of(),null,null).withRunId(1L).withWorkItem(3L,1);
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(LlmProvider.ChatResponse.stub("审查通过"));
        assertThat(service.review(ctx,tool,"原文")).isEqualTo("UNVERIFIED");
        when(caller.callIsolated(anyList(),anyList(),any())).thenReturn(LlmProvider.ChatResponse.text("PASS","test"));
        assertThat(service.review(ctx,tool,"原文")).isEqualTo("UNVERIFIED");
        verify(state,times(2)).review(eq(ctx),eq(new WorkItemState.Review("UNVERIFIED",List.of())));
    }
}
