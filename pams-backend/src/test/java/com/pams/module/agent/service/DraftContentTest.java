package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.AgentActionPlan;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DraftContentTest {
    final DraftContent drafts=new DraftContent(new HtmlSanitizer());
    AgentActionPlan plan(Map<String,Object> args) throws Exception {
        return new AgentActionPlan(null,"内容草稿",1,List.of(),List.of(),List.of("__args__:"+new ObjectMapper().writeValueAsString(args)),true,null,"旧的截断预览");
    }
    @Test void theActualPlanPreviewIncludesEveryChapterTail() throws Exception {
        var plan=plan(Map.of("activityId",42,"purpose","<p>"+"完整正文".repeat(300)+"完整尾部</p>"));
        assertThat(drafts.preview("draftPlanSections",plan)).contains("完整尾部").doesNotContain("截断预览");
        assertThat(drafts.fields("draftPlanSections",plan)).containsKey("purpose");
    }
    @Test void editsEscapeMarkupAndCannotReplaceServerOwnedEntityIds() throws Exception {
        var plan=plan(Map.of("activityId",42,"title","原题","content","<p>原文</p>"));
        var updated=drafts.replace("draftArticleFromActivity",plan,Map.of("title","改题","content","<img src=x onerror=alert(1)>"));
        assertThat(drafts.arguments(updated)).containsEntry("activityId",42);
        assertThat(drafts.arguments(updated).get("content").toString()).contains("&lt;img").doesNotContain("<img");
        assertThatThrownBy(()->drafts.replace("draftArticleFromActivity",plan,Map.of("title","改题","content","正文","activityId","99"))).isInstanceOf(com.pams.common.BizException.class);
    }
}
