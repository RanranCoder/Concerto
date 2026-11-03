package com.pams.module.content.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "article")
@SQLRestriction("deleted = 0")
public class Article implements OrgScoped {
    public enum ArticleStatus { DRAFT, PENDING, APPROVED, PUBLISHED, REJECTED }
    public enum ArticleType { PREHEAT, REPORT, VIDEO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long revision = 0L;
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
    @Column(columnDefinition = "TEXT")
    private String imageUrls;
    private LocalDateTime deadline;
    private String wxUrl;
    private Integer readCount = 0;
    private Integer likeCount = 0;
    private LocalDateTime deadlineRemindedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
