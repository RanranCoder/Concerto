package com.pams.module.activity.repository;

import com.pams.module.activity.entity.ScoreRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoreRecordRepository extends JpaRepository<ScoreRecord, Long> {
    List<ScoreRecord> findByActivityId(Long activityId);
}
