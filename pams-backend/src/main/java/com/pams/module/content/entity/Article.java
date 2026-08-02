package com.pams.module.content.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "article")
@SQLRestriction("deleted = 0")
public class Article {
    public enum ArticleStatus { DRAFT, PENDING, PUBLISHED, REJECTED }
    public enum ArticleType { PREHEAT, REPORT, VIDEO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 150)
    private String title;
    @Column(length = 500)
    private String summary;
    @Column(columnDefinition = "TEXT")
    private String content;
    private String coverUrl;
    private Long activityId;
    @Enumerated(EnumType.STRING)
    private ArticleType articleType;
    @Enumerated(EnumType.STRING)
    private ArticleStatus status;
    private Long authorId;
    private Long reviewerId;
    @Column(columnDefinition = "TEXT")
    private String reviewComment;
    private LocalDateTime publishTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
