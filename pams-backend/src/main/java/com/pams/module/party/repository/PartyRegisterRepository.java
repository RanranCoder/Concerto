package com.pams.module.party.repository;

import com.pams.module.party.entity.PartyRegister;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyRegisterRepository extends JpaRepository<PartyRegister, Long> {

    List<PartyRegister> findByMemberId(Long memberId);
}
