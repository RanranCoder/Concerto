package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.dto.ScoreRecordRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ScoreRecord;
import com.pams.module.activity.entity.ScoreRule;
import com.pams.module.activity.repository.ScoreRecordRepository;
import com.pams.module.activity.repository.ScoreRuleRepository;
import com.pams.module.activity.service.ScoreService;
import com.pams.module.activity.service.ActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ScoreServiceTest {

    ScoreRecordRepository repo;
    ScoreService service;

    @BeforeEach
    void setup() {
        repo = mock(ScoreRecordRepository.class);
        var rules = mock(ScoreRuleRepository.class);
        var activities = mock(ActivityService.class);
        var activity = new Activity();
        activity.setId(11L);
        activity.setOrgId(2L);
        when(activities.lockForChildWrite(11L)).thenReturn(activity);
        var rule = new ScoreRule();
        rule.setId(1L);
        rule.setFullMarks(28);
        when(rules.findByActivityIdOrderBySortOrderAsc(11L)).thenReturn(List.of(rule));
        service = new ScoreService(repo, rules, activities);
    }

    @Test
    void createRecordAcceptsTheMaximumAndInheritsActivityOrg() {
        when(repo.save(any())).thenAnswer(call -> {
            ScoreRecord record = call.getArgument(0);
            assertThat(record.getOrgId()).isEqualTo(2L);
            assertThat(record.getTotal()).isEqualTo(28);
            record.setId(42L);
            return record;
        });
        assertThat(service.createRecord(request("{\"1\":28}"))).isEqualTo(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "{\"2\":0}", "{\"1\":-1}", "{\"1\":29}", "{\"1\":1.5}",
            "{\"1\":\"1\"}", "{\"1\":0,\"1\":1}", "{\"1\":1} []"})
    void invalidScoreDoesNotChangeAnExistingRecord(String scores) {
        var record = new ScoreRecord();
        record.setActivityId(11L);
        record.setTotal(5);
        record.setDimensionScores("{\"1\":5}");
        when(repo.findById(42L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.createRecord(request(scores))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.updateRecord(42L, request(scores))).isInstanceOf(BizException.class);
        assertThat(record.getTotal()).isEqualTo(5);
        assertThat(record.getDimensionScores()).isEqualTo("{\"1\":5}");
        verify(repo, never()).save(any());
    }

    private ScoreRecordRequest request(String scores) {
        var request = new ScoreRecordRequest();
        request.setActivityId(11L);
        request.setTeamName("队伍");
        request.setDimensionScores(scores);
        return request;
    }
}
