package com.pams.module.party.repository;

import com.pams.module.party.entity.PartyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface PartyMemberRepository extends JpaRepository<PartyMember, Long>,
        JpaSpecificationExecutor<PartyMember> {

    Optional<PartyMember> findByStudentNo(String studentNo);

    boolean existsByStudentNo(String studentNo);
}
