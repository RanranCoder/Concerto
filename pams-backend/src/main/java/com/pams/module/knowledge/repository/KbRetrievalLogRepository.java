package com.pams.module.knowledge.repository;

import com.pams.module.knowledge.entity.KbRetrievalLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbRetrievalLogRepository extends JpaRepository<KbRetrievalLog, Long> {

    Page<KbRetrievalLog> findAllByOrderByIdDesc(Pageable pageable);

    Page<KbRetrievalLog> findBySkipReasonOrderByIdDesc(String skipReason, Pageable pageable);

    long countBySkipReason(String skipReason);

    long countByFilteredCountGreaterThan(int threshold);
}
