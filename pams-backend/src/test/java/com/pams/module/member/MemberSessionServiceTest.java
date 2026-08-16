package com.pams.module.member;

import com.pams.common.BizException;
import com.pams.module.member.dto.MemberSessionRequest;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.module.member.service.MemberSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MemberSessionServiceTest {

    MemberSessionRepository sessionRepo;
    MemberRepository memberRepo;
    MemberSessionService service;

    @BeforeEach
    void setup() {
        sessionRepo = mock(MemberSessionRepository.class);
        memberRepo = mock(MemberRepository.class);
        service = new MemberSessionService(sessionRepo, memberRepo);
    }

    @Test
    void create_rejectsDuplicateName() {
        when(sessionRepo.existsByName("第九届")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new MemberSessionRequest("第九届", 1, 1, null)))
            .isInstanceOf(BizException.class).hasMessageContaining("届别");
    }

    @Test
    void delete_blocksWhenMembersExist() {
        MemberSession s = new MemberSession(); s.setId(1L);
        when(sessionRepo.findById(1L)).thenReturn(Optional.of(s));
        when(memberRepo.countBySessionId(1L)).thenReturn(5L);
        assertThatThrownBy(() -> service.delete(1L))
            .isInstanceOf(BizException.class).hasMessageContaining("成员");
        verify(sessionRepo, never()).delete(s);
    }

    @Test
    void setCurrent_clearsOthersThenSetsTarget() {
        when(sessionRepo.findById(2L)).thenReturn(Optional.of(new MemberSession()));
        service.setCurrent(2L);
        verify(sessionRepo).clearCurrentFlag();
        verify(sessionRepo).save(any(MemberSession.class));
    }

    @Test
    void list_ordersCurrentFirst() {
        MemberSession old = new MemberSession(); old.setName("第八届"); old.setIsCurrent(0);
        MemberSession cur = new MemberSession(); cur.setName("第九届"); cur.setIsCurrent(1);
        when(sessionRepo.findAllByOrderByIsCurrentDescSortOrderAscIdAsc()).thenReturn(List.of(cur, old));
        var list = service.list();
        assertThat(list.get(0).name()).isEqualTo("第九届");
        assertThat(list.get(1).name()).isEqualTo("第八届");
    }
}
