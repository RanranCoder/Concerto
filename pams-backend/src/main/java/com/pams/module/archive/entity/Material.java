package com.pams.module.archive.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "material")
@SQLRestriction("deleted = 0")
public class Material {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 150)
    private String name;
    @Column(nullable = false, length = 30)
    private String bizType;
    private Long activityId;
    private Long deptId;
    private Long uploaderId;
    @Column(length = 200)
    private String tag;
    @Column(length = 500)
    private String description;
    private Long fileId;
    private LocalDateTime createdAt;
    private Integer deleted;
}
