package com.pams.module.member.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "member")
@SQLRestriction("deleted = 0")
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "dept_id")
    private Long deptId;
    @Column(nullable = false, length = 20)
    private String position;
    @Column(nullable = false, length = 50)
    private String name;
    @Column(length = 2)
    private String gender;
    @Column(name = "student_no", length = 30)
    private String studentNo;
    @Column(name = "class_name", length = 100)
    private String className;
    @Column(length = 20)
    private String phone;
    @Column(name = "political_status", length = 20)
    private String politicalStatus;
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";
    @Column(length = 255)
    private String remark;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    private Integer deleted = 0;
}
