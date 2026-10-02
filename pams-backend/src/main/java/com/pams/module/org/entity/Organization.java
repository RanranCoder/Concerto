package com.pams.module.org.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 组织（租户）。注册组织的产物，驱动品牌主题、启用的领域包与术语表。
 * 对应迁移 V30 {@code organization}。
 */
@Data
@Entity
@Table(name = "organization")
@SQLRestriction("deleted = 0")
public class Organization {

    /** 组织类型 */
    public static final String TYPE_PARTY = "PARTY";
    public static final String TYPE_STUDENT_UNION = "STUDENT_UNION";
    public static final String TYPE_CLUB = "CLUB";
    public static final String TYPE_VOLUNTEER = "VOLUNTEER";
    public static final String TYPE_SCIENCE = "SCIENCE";
    public static final String TYPE_OTHER = "OTHER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String type = TYPE_OTHER;
    private String shortName;
    private String logoUrl;

    /** 主题令牌 JSON，如 {"primaryColor":"#2F6FED"} */
    @Column(columnDefinition = "JSON")
    private String themeJson;

    /** 启用的领域包 JSON 数组，如 ["party","volunteer"] */
    @Column(columnDefinition = "JSON")
    private String enabledPacks;

    /** 术语表 JSON，如 {"activity":"活动","member":"成员"} */
    @Column(columnDefinition = "JSON")
    private String terminologyJson;

    private Integer status = 1;
    private Long ownerUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted = 0;
}
