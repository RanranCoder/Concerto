package com.pams.module.member.repository;

import com.pams.module.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {
    List<Member> findBySessionId(Long sessionId);
    boolean existsBySessionIdAndStudentNo(Long sessionId, String studentNo);
    long countBySessionId(Long sessionId);

    @Modifying
    @Transactional
    @Query("update Member m set m.status = 'ALUMNI', m.updatedAt = :now " +
           "where m.sessionId = :sessionId and m.status = 'ACTIVE' and m.deleted = 0")
    int archiveSession(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);
}
