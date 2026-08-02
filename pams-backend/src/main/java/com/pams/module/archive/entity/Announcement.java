package com.pams.module.archive.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "announcement")
@SQLRestriction("deleted = 0")
public class Announcement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 150)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    private Long publisherId;
    private LocalDateTime publishTime;
    private LocalDateTime createdAt;
    private Integer deleted;
}
