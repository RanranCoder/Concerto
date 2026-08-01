package com.pams.module.activity.repository;

import com.pams.module.activity.entity.ActivityAgenda;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityAgendaRepository extends JpaRepository<ActivityAgenda, Long> {
    List<ActivityAgenda> findByActivityIdOrderByStepNoAsc(Long activityId);
}
