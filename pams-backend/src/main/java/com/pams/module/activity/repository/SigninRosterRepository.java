package com.pams.module.activity.repository;

import com.pams.module.activity.entity.SigninRoster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SigninRosterRepository extends JpaRepository<SigninRoster, Long> {
    List<SigninRoster> findByActivityId(Long activityId);
    List<SigninRoster> findByGroupId(Long groupId);
    void deleteByActivityId(Long activityId);
    void deleteByGroupId(Long groupId);
    long countByActivityId(Long activityId);
    long countByGroupId(Long groupId);
}
