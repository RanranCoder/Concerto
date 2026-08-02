package com.pams.module.party.repository;

import com.pams.module.party.entity.PartyRoster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyRosterRepository extends JpaRepository<PartyRoster, Long> {

    List<PartyRoster> findByRosterType(String rosterType);

    List<PartyRoster> findByIssueNo(String issueNo);
}
