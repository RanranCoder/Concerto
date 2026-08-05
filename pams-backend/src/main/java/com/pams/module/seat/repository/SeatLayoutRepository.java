package com.pams.module.seat.repository;

import com.pams.module.seat.entity.SeatLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeatLayoutRepository extends JpaRepository<SeatLayout, Long> {
    Optional<SeatLayout> findTopByActivityIdOrderByUpdatedAtDesc(Long activityId);
    List<SeatLayout> findByActivityIdOrderByUpdatedAtDesc(Long activityId);
    List<SeatLayout> findByIsTemplateOrderByIdAsc(Integer isTemplate);
}
