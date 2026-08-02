package com.pams.module.party.repository;

import com.pams.module.party.entity.PartyTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyTransferRepository extends JpaRepository<PartyTransfer, Long> {

    List<PartyTransfer> findByMemberId(Long memberId);
}
