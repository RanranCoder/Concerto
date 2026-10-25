package com.pams.module.agent.core;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
class BusinessLinksTest {
    @ParameterizedTest @CsvSource({
        "/activity/12,/activities/12", "/activity/12/tasks,/activities/12/gantt",
        "/activity/12/seat,/activities/12?tab=seat", "/activity/12/signin,/activities/12?tab=signin",
        "/member?id=4,/members/4", "/party/member?id=4,/party/members/4",
        "/content/article?id=5,/content/articles?id=5", "/archive/material?id=5,/archive/materials?id=5",
        "/archive/credit?activityId=12,/archive/credits?activityId=12",
        "/archive/announcement?id=5,/archive/announcements?id=5", "/notification,/notifications",
        "/activities/12?tab=plan,/activities/12?tab=plan"
    }) void resolvesCurrentAndHistoricalLinks(String input,String expected) { assertThat(BusinessLinks.normalize(input)).isEqualTo(expected); }
}
