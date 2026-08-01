package com.pams.module.activity.repository;

import com.pams.module.activity.entity.ActivityPlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityPlanRepository extends JpaRepository<ActivityPlan, Long> {
}
