package com.pams.module.schedule.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 个人课程表（PRD F08）
 */
@Data
@Entity
@Table(name = "course_schedule")
public class CourseSchedule implements OrgScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 20)
    private String semester;

    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    @Column(nullable = false)
    private Integer period;

    @Column(name = "course_name", length = 100)
    private String courseName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
