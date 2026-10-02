package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Signin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SigninRepository extends JpaRepository<Signin, Long> {
    List<Signin> findByActivityId(Long activityId);
    long countByActivityId(Long activityId);
    List<Signin> findByActivityIdAndNameContaining(Long activityId, String name);
    List<Signin> findBySessionId(Long sessionId);
    Optional<Signin> findBySessionIdAndStudentNo(Long sessionId, String studentNo);
    Optional<Signin> findBySessionIdAndName(Long sessionId, String name);
}
