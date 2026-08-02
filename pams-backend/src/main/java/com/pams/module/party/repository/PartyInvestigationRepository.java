package com.pams.module.party.repository;

import com.pams.module.party.entity.PartyInvestigation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyInvestigationRepository extends JpaRepository<PartyInvestigation, Long> {

    List<PartyInvestigation> findByMemberId(Long memberId);
}
