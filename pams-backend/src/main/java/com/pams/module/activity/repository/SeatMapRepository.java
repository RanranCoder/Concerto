package com.pams.module.activity.repository;

import com.pams.module.activity.entity.SeatMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatMapRepository extends JpaRepository<SeatMap, Long> {
    List<SeatMap> findByActivityIdOrderByZoneAscRowNoAscColNoAsc(Long activityId);
}
