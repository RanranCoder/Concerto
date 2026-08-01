package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Signin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SigninRepository extends JpaRepository<Signin, Long> {
    List<Signin> findByActivityId(Long activityId);
    long countByActivityId(Long activityId);
    List<Signin> findByActivityIdAndNameContaining(Long activityId, String name);
}
