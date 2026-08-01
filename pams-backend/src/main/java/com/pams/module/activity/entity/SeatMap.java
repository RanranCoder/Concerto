package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "seat_map")
public class SeatMap {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private String roomName;
    private String zone;
    private Integer rowNo;
    private Integer colNo;
    private String personName;
    private String seatType;
    private LocalDateTime createdAt;
}
