package com.pams.module.activity.repository;

import com.pams.module.activity.entity.SigninRoster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SigninRosterRepository extends JpaRepository<SigninRoster, Long> {
    List<SigninRoster> findByActivityId(Long activityId);
    void deleteByActivityId(Long activityId);
    long countByActivityId(Long activityId);
}
