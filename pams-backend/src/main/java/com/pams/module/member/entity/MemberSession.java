package com.pams.module.member.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "member_session")
@SQLRestriction("deleted = 0")
public class MemberSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 50)
    private String name;
    @Column(name = "is_current")
    private Integer isCurrent = 0;
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
    @Column(length = 255)
    private String remark;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    private Integer deleted = 0;
}
