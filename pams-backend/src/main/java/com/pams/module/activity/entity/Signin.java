package com.pams.module.activity.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "signin")
public class Signin implements OrgScoped {
    public enum SignType { MANUAL, SCAN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private Long sessionId;
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
    /** 扫码提交的自定义表单字段值（JSON：字段名->值），供签到记录回显 */
    @Column(columnDefinition = "TEXT")
    private String fieldsJson;
    private LocalDateTime createdAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
