package com.pams.module.agent.service;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class AgentContextMetricsTest {
    ToolContext ctx(long user,long org){return new ToolContext(new LoginUser(user,"u","用户","DIRECTOR",4,null,null,org),List.of(),null,null);}
    @Test void ownerIsolationAndAbsentUsageAreHonest() {
        var metrics=new AgentContextMetrics();var user=ctx(1,1);
        metrics.memoryRead(user,2);metrics.memoryRead(user,0);
        metrics.summary(user,100,()->{assertThat(metrics.snapshot(user).get("summaryMeanMs")).isNull();metrics.usage(new LlmProvider.Usage(120,30,null,null));return "ok";});
        metrics.written(user);metrics.cacheHit(user);
        var view=metrics.snapshot(user);
        assertThat(view).containsEntry("memoryHits",1L).containsEntry("memoryReads",2L).containsEntry("inputTokens",120L).containsEntry("outputTokens",30L);
        assertThat(metrics.snapshot(ctx(2,1))).containsEntry("summaryCalls",0L).containsEntry("inputTokens",null);
        assertThat(metrics.snapshot(ctx(1,2))).containsEntry("memoryReads",0L);
        assertThat(view.keySet()).doesNotContain("prompt","content","conversationId","userId");
    }
    @Test void failedModelCallIsCountedAndThreadContextIsReleased() {
        var metrics=new AgentContextMetrics();var user=ctx(1,1);
        assertThatThrownBy(()->metrics.summary(user,20,()->{throw new IllegalStateException("failure");})).isInstanceOf(IllegalStateException.class);
        metrics.usage(new LlmProvider.Usage(999,999,null,null));
        assertThat(metrics.snapshot(user)).containsEntry("summaryCalls",1L).containsEntry("summaryFailures",1L).containsEntry("inputTokens",null);
    }
}
