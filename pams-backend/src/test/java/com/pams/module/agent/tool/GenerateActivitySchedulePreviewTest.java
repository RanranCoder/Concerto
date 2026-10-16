package com.pams.module.agent.tool;

import com.pams.module.activity.entity.Activity;
import com.pams.module.routine.service.ActivityScheduleService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerateActivitySchedulePreviewTest {
    @Test void toolPreviewsBeforeWritingAndKeepsTheFingerprintThroughConfirmation() {
        var service=mock(ActivityScheduleService.class);var resolver=mock(ActivityResolver.class);
        var activity=new Activity();activity.setId(1L);activity.setName("培训班");
        when(resolver.resolve(any(),any())).thenReturn(new ActivityResolver.Resolution(activity,null));
        var rows=List.<Map<String,Object>>of(Map.of("date","2026-10-15","count",2,"slots",List.of(
                Map.of("startTime","14:00","endTime","15:40","count",2,"missingCount",0,"persons",List.of(Map.of("name","甲同学"),Map.of("name","乙同学"))),
                Map.of("startTime","16:00","endTime","17:40","count",0,"missingCount",2,"persons",List.of()))));
        when(service.preview(any())).thenReturn(rows);
        when(service.generateReviewed(any(),any())).thenReturn(rows);
        var tool=new GenerateActivityScheduleTool(service,resolver);
        var result=tool.execute(null,Map.of("activityId",1,"startDate","2026-10-15","endDate","2026-10-15","startTime","14:00","endTime","17:40","deptIds",List.of(1),"peoplePerSlot",2));
        var plan=result.plan();
        assertThat(plan.total()).isEqualTo(1);
        assertThat(plan.preview()).contains("甲同学","乙同学","无候选");
        assertThat(plan.skipped()).hasSize(1);
        verify(service,never()).generateReviewed(any(),any());
        var committed=tool.commit(null,plan);
        assertThat(committed.status()).isEqualTo("PARTIAL");
        assertThat(committed.detail().get("slots")).isEqualTo(1);
        verify(service).generateReviewed(any(),eq(ActivityScheduleService.previewFingerprint(rows)));
    }
}
