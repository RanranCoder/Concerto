package com.pams.module.agent.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolResultTextTest {
    @Test void normalReadContextCarriesActualIdsDatesLocationsAndReferences() {
        var tool=mock(AgentTool.class,CALLS_REAL_METHODS);when(tool.sensitivity()).thenReturn(Sensitivity.NORMAL);
        var result=ToolResult.ok("匹配到活动",Map.of("rows",List.of(Map.of("id",412,"startDate",java.time.LocalDate.of(2026,11,20),"location","S5 测试礼堂"))),List.of(Map.of("type","activity","id",412,"link","/activities/412")));
        assertThat(tool.toLlmText(null,result)).contains("412","2026-11-20","S5 测试礼堂","references");
    }
    @Test void sensitiveAndWriteResultsKeepSummaryOnlyAndNeverSendConfirmationArguments() {
        var tool=mock(AgentTool.class,CALLS_REAL_METHODS);when(tool.sensitivity()).thenReturn(Sensitivity.SENSITIVE);
        var result=ToolResult.ok("安全摘要",Map.of("privateBody","不要发送","confirmToken","secret"));
        assertThat(tool.toLlmText(null,result)).isEqualTo("安全摘要");
        when(tool.sensitivity()).thenReturn(Sensitivity.NORMAL);when(tool.writeOperation()).thenReturn(true);
        assertThat(tool.toLlmText(null,result)).isEqualTo("安全摘要");
    }
    @Test void oversizedResultsStayBoundedAndReportTruncationWithoutMalformedJson() throws Exception {
        var tool=mock(AgentTool.class,CALLS_REAL_METHODS);when(tool.sensitivity()).thenReturn(Sensitivity.NORMAL);
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<100;i++)rows.add(Map.of("id",i,"text","正文".repeat(3000),"confirmToken","must-not-leave"));
        String context=tool.toLlmText(null,ToolResult.ok("结果",Map.of("rows",rows)));
        assertThat(context.length()).isLessThan(12100);assertThat(context).doesNotContain("must-not-leave");
        var parsed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(context.substring(context.indexOf('{')));
        assertThat(parsed.path("truncated").asBoolean()).isTrue();assertThat(parsed.path("data").path("rows").isArray()).isTrue();
    }
}
