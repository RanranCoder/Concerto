package com.pams.module.archive.repository;

import com.pams.module.archive.entity.CreditRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CreditRecordRepository extends JpaRepository<CreditRecord, Long>,
        JpaSpecificationExecutor<CreditRecord> {
}
