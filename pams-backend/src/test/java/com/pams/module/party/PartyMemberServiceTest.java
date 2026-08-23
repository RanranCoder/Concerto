package com.pams.module.party;

import com.pams.common.BizException;
import com.pams.module.party.dto.PartyMemberRequest;
import com.pams.module.party.entity.PartyMember;
import com.pams.module.party.entity.PartyStage;
import com.pams.module.party.entity.PartyStageType;
import com.pams.module.party.repository.PartyMemberRepository;
import com.pams.module.party.repository.PartyStageRepository;
import com.pams.module.party.service.PartyMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PartyMemberServiceTest {

    PartyMemberRepository memberRepo;
    PartyStageRepository stageRepo;
    PartyMemberService service;

    @BeforeEach
    void setup() {
        memberRepo = mock(PartyMemberRepository.class);
        stageRepo = mock(PartyStageRepository.class);
        service = new PartyMemberService(memberRepo, stageRepo);
    }

    @Test
    void changeStage_appendsRecord_andUpdatesPoliticalStatus() {
        PartyMember m = new PartyMember();
        m.setId(1L);
        m.setPoliticalStatus("共青团员");
        when(memberRepo.findById(1L)).thenReturn(Optional.of(m));

        service.changeStage(1L, "ACTIVE", "40", "2026-01-01", null, null);

        assertThat(m.getPoliticalStatus()).isEqualTo("入党积极分子");
        verify(stageRepo).save(any(PartyStage.class));
        verify(memberRepo).save(m);
    }

    @Test
    void changeStage_unknownMember_throws() {
        when(memberRepo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeStage(9L, "ACTIVE", "40", null, null, null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void changeStage_invalidStage_throwsBiz() {
        PartyMember m = new PartyMember();
        m.setId(1L);
        when(memberRepo.findById(1L)).thenReturn(Optional.of(m));
        when(stageRepo.findByMemberIdOrderByStartDateAsc(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.changeStage(1L, "NOT_A_STAGE", "40", null, null, null))
                .isInstanceOf(BizException.class).hasMessageContaining("阶段");
        verify(stageRepo, never()).save(any(PartyStage.class));
    }

    @Test
    void changeStage_outOfOrder_throws() {
        PartyMember m = new PartyMember();
        m.setId(1L);
        when(memberRepo.findById(1L)).thenReturn(Optional.of(m));
        PartyStage current = new PartyStage();
        current.setId(10L);
        current.setMemberId(1L);
        current.setStage(PartyStageType.PROBATIONARY);
        current.setStatus("CURRENT");
        when(stageRepo.findByMemberIdOrderByStartDateAsc(1L)).thenReturn(List.of(current));

        // 回退（预备党员 -> 入党积极分子）应被拒绝
        assertThatThrownBy(() -> service.changeStage(1L, "ACTIVE", "40", null, null, null))
                .isInstanceOf(BizException.class).hasMessageContaining("顺序");
    }

    @Test
    void changeStage_advancesAndDemotesPreviousCurrent() {
        PartyMember m = new PartyMember();
        m.setId(1L);
        m.setPoliticalStatus("入党积极分子");
        when(memberRepo.findById(1L)).thenReturn(Optional.of(m));
        PartyStage old = new PartyStage();
        old.setId(10L);
        old.setMemberId(1L);
        old.setStage(PartyStageType.ACTIVE);
        old.setStatus("CURRENT");
        when(stageRepo.findByMemberIdOrderByStartDateAsc(1L)).thenReturn(List.of(old));

        service.changeStage(1L, "DEVELOPMENT", "40", "2026-01-01", null, null);

        assertThat(old.getStatus()).isEqualTo("HISTORY");
        assertThat(m.getPoliticalStatus()).isEqualTo("重点发展对象");
        verify(stageRepo, times(2)).save(any(PartyStage.class));
    }

    @Test
    void update_rejectsDuplicateStudentNo() {
        PartyMember m = new PartyMember();
        m.setId(1L);
        when(memberRepo.findById(1L)).thenReturn(Optional.of(m));
        PartyMemberRequest req = new PartyMemberRequest();
        req.setStudentNo("2026001");
        when(memberRepo.existsByStudentNoAndIdNot("2026001", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BizException.class).hasMessageContaining("学号");
        verify(memberRepo, never()).save(any(PartyMember.class));
    }

    @Test
    void update_allowsOwnStudentNo() {
        PartyMember m = new PartyMember();
        m.setId(1L);
        when(memberRepo.findById(1L)).thenReturn(Optional.of(m));
        PartyMemberRequest req = new PartyMemberRequest();
        req.setName("张三");
        req.setStudentNo("2026001");
        when(memberRepo.existsByStudentNoAndIdNot("2026001", 1L)).thenReturn(false);

        service.update(1L, req);

        assertThat(m.getStudentNo()).isEqualTo("2026001");
        verify(memberRepo).save(m);
    }
}
