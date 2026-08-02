package com.pams.module.archive.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "template_asset")
@SQLRestriction("deleted = 0")
public class TemplateAsset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 150)
    private String name;
    @Column(nullable = false, length = 30)
    private String category;
    @Column(length = 500)
    private String description;
    private Long fileId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Integer deleted;
}
