package com.pams.module.agent.service;

import com.pams.module.agent.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AgentEvaluationMeterTest {
    @Test void parallelCallsShareOneBudgetAndCannotSpendAnotherStreamsBudget() throws Exception {
        var meter=new AgentEvaluationMeter();meter.open("a",3,256);meter.open("b",1,512);
        var admitted=new AtomicInteger();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<12;i++)jobs.add(pool.submit(()->{
                try { assertThat(meter.before("a",8192)).isEqualTo(256);admitted.incrementAndGet(); }
                catch(AgentEvaluationMeter.LimitExceeded expected) { }
            }));for(var job:jobs)job.get();
        }
        assertThat(admitted.get()).isEqualTo(3);assertThat(meter.before("b",8192)).isEqualTo(512);
        assertThat(meter.before("ordinary-request",8192)).isEqualTo(8192);
        assertThat(meter.close("a")).containsEntry("attempts",3).containsEntry("usageComplete",false);
        assertThat(meter.snapshot("a")).isEmpty();
    }
    @Test void missingUsageAndFailuresDoNotBecomeZeroCostOrCompleteAccounting() {
        var meter=new AgentEvaluationMeter();meter.open("s",4,512);
        meter.before("s",512);meter.record("s",new LlmProvider.ChatResponse("",List.of(),"deepseek",false,null,new LlmProvider.Usage(120,30,10L,110L),"actual-model","stop"),2,false);
        meter.before("s",512);meter.record("s",null,3,true);
        meter.before("s",512);meter.record("s",LlmProvider.ChatResponse.stub("fallback"),1,false);
        var result=meter.close("s");
        assertThat(result).containsEntry("reportedInputTokens",120L).containsEntry("reportedOutputTokens",30L)
            .containsEntry("failures",1).containsEntry("degradedResponses",1).containsEntry("usageComplete",false).containsEntry("cost",null);
        assertThat(result.get("models")).isEqualTo(List.of("actual-model"));
        assertThat(result.toString()).doesNotContain("fallback","apiKey","messages");
    }
}
