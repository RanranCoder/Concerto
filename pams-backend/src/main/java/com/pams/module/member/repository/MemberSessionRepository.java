package com.pams.module.member.repository;

import com.pams.module.member.entity.MemberSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MemberSessionRepository extends JpaRepository<MemberSession, Long> {
    List<MemberSession> findAllByOrderByIsCurrentDescSortOrderAscIdAsc();
    boolean existsByName(String name);

    @Modifying
    @Transactional
    @Query("update MemberSession s set s.isCurrent = 0")
    int clearCurrentFlag();
}
