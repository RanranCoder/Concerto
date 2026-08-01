package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ActivityRepository extends JpaRepository<Activity, Long>,
        JpaSpecificationExecutor<Activity> {
}
