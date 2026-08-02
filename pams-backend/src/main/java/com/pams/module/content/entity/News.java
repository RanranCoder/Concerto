package com.pams.module.content.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "news")
@SQLRestriction("deleted = 0")
public class News {
    public enum NewsStatus { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 150)
    private String title;
    @Column(length = 300)
    private String subtitle;
    @Column(columnDefinition = "TEXT")
    private String content;
    private Long activityId;
    private Long authorId;
    private LocalDate publishDate;
    @Enumerated(EnumType.STRING)
    private NewsStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
