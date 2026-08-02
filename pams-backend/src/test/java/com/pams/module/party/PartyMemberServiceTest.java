package com.pams.module.party;

import com.pams.common.BizException;
import com.pams.module.party.entity.PartyMember;
import com.pams.module.party.entity.PartyStage;
import com.pams.module.party.repository.PartyMemberRepository;
import com.pams.module.party.repository.PartyStageRepository;
import com.pams.module.party.service.PartyMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
