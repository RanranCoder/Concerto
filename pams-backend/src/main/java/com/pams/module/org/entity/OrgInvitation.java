package com.pams.module.org.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织邀请入组。对应迁移 V30 {@code org_invitation}。
 */
@Data
@Entity
@Table(name = "org_invitation")
public class OrgInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orgId;
    private String code;
    private String roleCode;

    /** 预约部门（= sys_department.id，组织架构节点） */
    private Long orgUnitId;
    private LocalDateTime expireAt;
    private Long usedBy;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
}
