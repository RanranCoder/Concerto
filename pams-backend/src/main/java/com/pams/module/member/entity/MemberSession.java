package com.pams.module.member.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "member_session")
@SQLRestriction("deleted = 0")
public class MemberSession implements OrgScoped {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 唯一性由 V34 的复合键 (org_id, name, deleted) 保证（组织内唯一）
    @Column(nullable = false, length = 50)
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
    private Long deleted = 0L;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
