package com.pams.module.archive;

import com.pams.module.archive.dto.CreditRequest;
import com.pams.module.archive.entity.CreditRecord;
import com.pams.module.archive.repository.CreditRecordRepository;
import com.pams.module.archive.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditServiceTest {

    CreditRecordRepository repo;
    CreditService service;

    @BeforeEach
    void setup() {
        repo = mock(CreditRecordRepository.class);
        service = new CreditService(repo);
    }

    private CreditRequest req(BigDecimal credit) {
        CreditRequest r = new CreditRequest();
        r.setUserId(7L);
        r.setPersonName("张三");
        r.setStudentNo("2024001");
        r.setActivityId(3L);
        r.setProject("志愿服务");
        r.setCredit(credit);
        r.setBasis("PARTICIPATE");
        r.setRemark("note");
        return r;
    }

    @Test
    void create_roundsCreditToTwoDecimals() {
        when(repo.save(any(CreditRecord.class))).thenAnswer(inv -> {
            CreditRecord c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        CreditRecord saved = service.create(99L, req(new BigDecimal("0.5")));

        assertThat(saved.getCredit()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(saved.getCredit().setScale(2, RoundingMode.HALF_UP)).isEqualTo(new BigDecimal("0.50"));
        assertThat(saved.getRecordBy()).isEqualTo(99L);
        assertThat(saved.getBasis()).isEqualTo("PARTICIPATE");
        verify(repo).save(any(CreditRecord.class));
    }

    @Test
    void create_accumulatesCreditWithTwoDecimalPrecision() {
        when(repo.save(any(CreditRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        CreditRecord a = service.create(99L, req(new BigDecimal("1.1")));
        CreditRecord b = service.create(99L, req(new BigDecimal("2.2")));

        BigDecimal sum = a.getCredit().add(b.getCredit());
        assertThat(sum.setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(new BigDecimal("3.30"));
    }

    @Test
    void create_roundsHalfUpOnThirdDecimal() {
        when(repo.save(any(CreditRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        CreditRecord saved = service.create(99L, req(new BigDecimal("1.235")));

        assertThat(saved.getCredit()).isEqualByComparingTo(new BigDecimal("1.24"));
    }

    @Test
    void update_overwritesExistingRecord() {
        CreditRecord existing = new CreditRecord();
        existing.setId(5L);
        existing.setPersonName("李四");
        existing.setCredit(new BigDecimal("1.00"));
        when(repo.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, req(new BigDecimal("2.00")));

        assertThat(existing.getCredit()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(existing.getPersonName()).isEqualTo("张三");
        assertThat(existing.getActivityId()).isEqualTo(3L);
    }
}
