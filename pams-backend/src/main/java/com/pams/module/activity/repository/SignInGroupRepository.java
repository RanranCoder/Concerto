package com.pams.module.activity.repository;

import com.pams.module.activity.entity.SignInGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SignInGroupRepository extends JpaRepository<SignInGroup, Long> {
    List<SignInGroup> findByActivityIdOrderBySortOrderAsc(Long activityId);
    Optional<SignInGroup> findByActivityIdAndGroupName(Long activityId, String groupName);
    List<SignInGroup> findByActivityId(Long activityId);
    void deleteByActivityId(Long activityId);
}
