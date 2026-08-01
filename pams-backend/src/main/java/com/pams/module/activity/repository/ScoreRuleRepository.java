package com.pams.module.activity.repository;

import com.pams.module.activity.entity.ScoreRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoreRuleRepository extends JpaRepository<ScoreRule, Long> {
    List<ScoreRule> findByActivityIdOrderBySortOrderAsc(Long activityId);
}
