package com.pams.module.org.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 组织职务（主席/部长/干事…）到系统角色的映射。对应迁移 V30 {@code org_position}。
 */
@Data
@Entity
@Table(name = "org_position")
public class OrgPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orgId;
    private String name;
    private Integer level;

    /** 映射的系统角色码：TEACHER/DIRECTOR/*_LEADER/STAFF */
    private String roleCode;
    private Integer sortOrder;
}
