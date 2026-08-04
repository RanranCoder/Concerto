package com.pams.module.activity.repository;

import com.pams.module.activity.entity.SigninFieldConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SigninFieldConfigRepository extends JpaRepository<SigninFieldConfig, Long> {
    List<SigninFieldConfig> findByActivityIdOrderBySortOrderAsc(Long activityId);
    void deleteByActivityId(Long activityId);
}
