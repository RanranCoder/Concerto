package com.pams.module.party.repository;

import com.pams.module.party.entity.PartyStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyStageRepository extends JpaRepository<PartyStage, Long> {

    List<PartyStage> findByMemberIdOrderByStartDateAsc(Long memberId);
}
