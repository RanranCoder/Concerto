package com.pams.module.seat.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 座位表布局（PRD F01）
 * seat_data 为 JSON 文本：[{row, col, type, color, label, state}]
 */
@Data
@Entity
@Table(name = "seat_layout")
public class SeatLayout {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id")
    private Long activityId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "`rows`", nullable = false)
    private Integer rows;

    @Column(name = "`cols`", nullable = false)
    private Integer cols;

    @Column(name = "aisle_cols", length = 255)
    private String aisleCols;

    @Column(name = "aisle_width_ratio", precision = 3, scale = 1)
    private java.math.BigDecimal aisleWidthRatio;

    @Column(name = "seat_data", columnDefinition = "TEXT")
    private String seatData;

    @Column(name = "color_labels", columnDefinition = "TEXT")
    private String colorLabels;

    @Column(name = "is_template")
    private Integer isTemplate;

    @Column(name = "template_category", length = 50)
    private String templateCategory;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
