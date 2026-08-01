package com.pams.module.activity;

import com.pams.module.activity.entity.ScoreRecord;
import com.pams.module.activity.repository.ScoreRecordRepository;
import com.pams.module.activity.service.ScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ScoreServiceTest {

    ScoreRecordRepository repo;
    ScoreService service;

    @BeforeEach
    void setup() {
        repo = mock(ScoreRecordRepository.class);
        service = new ScoreService(repo);
    }

    @Test
    void computeTotal_sumsDimensions() {
        ScoreRecord r = new ScoreRecord();
        r.setDimensionScores("{\"1\":28,\"2\":18,\"3\":16}");
        int total = service.computeTotal(r.getDimensionScores());
        assertThat(total).isEqualTo(62);
    }

    @Test
    void computeTotal_malformed_returnsZero() {
        assertThat(service.computeTotal("not-json")).isEqualTo(0);
    }
}
