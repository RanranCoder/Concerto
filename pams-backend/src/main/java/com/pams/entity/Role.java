package com.pams.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sys_role")
public class Role implements OrgScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private Integer level;
    private String dataScope;
    private LocalDateTime createdAt;
    private Long orgId;
    private String status = "ACTIVE";
    private String description;
    private Long configVersion = 0L;
    private Long currentRevisionId;
    private Long draftRevisionId;
}
