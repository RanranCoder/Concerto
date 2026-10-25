package com.pams.module.agent.tool;
import com.pams.module.activity.entity.Activity;
import com.pams.module.agent.core.ToolContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DraftArticleFactsTest {
    @Test void anActivityDraftDoesNotInventSuccessfulCompletion() {
        var activity=new Activity();activity.setId(1L);activity.setName("未来测试活动");activity.setStartDate(java.time.LocalDate.now().plusDays(30));activity.setLocation("测试礼堂");
        var resolver=mock(ActivityResolver.class);when(resolver.resolve(1L,null)).thenReturn(new ActivityResolver.Resolution(activity,null));
        var service=mock(com.pams.module.content.service.ArticleService.class);
        var tool=new DraftArticleFromActivityTool(service,new com.pams.module.agent.service.HtmlSanitizer(),resolver);
        var result=tool.execute(ToolContext.current(null,null),Map.of("activityId",1));
        assertThat(result.plan().preview()).doesNotContain("顺利开展","成功举办").contains(activity.getStartDate().toString(),"测试礼堂");
        assertThat(result.plan().summary()).doesNotContain("顺利开展");verifyNoInteractions(service);
    }
}
