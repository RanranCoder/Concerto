package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "signin")
public class Signin {
    public enum SignType { MANUAL, SCAN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private Long personId;
    private String name;
    private String studentNo;
    private String className;
    private String identityType;
    @Enumerated(EnumType.STRING)
    private SignType signType;
    private LocalDateTime signTime;
    private String location;
    private String phone;
    private String remark;
    private LocalDateTime createdAt;
}
