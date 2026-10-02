package com.pams.module.activity.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "signin_roster")
public class SigninRoster implements OrgScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private Long groupId;
    @Column(columnDefinition = "TEXT")
    private String fieldsJson;
    private LocalDateTime createdAt;
    private Long orgId;
}
