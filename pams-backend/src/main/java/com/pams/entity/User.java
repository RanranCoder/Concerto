package com.pams.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sys_user")
@SQLRestriction("deleted = 0")
public class User implements OrgScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password;
    private String realName;
    private String studentNo;
    private String phone;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "dept_id")
    private Department dept;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;
    private Integer status;
    /** 审计 #3：用户级 token 吊销水位——iat 早于该时刻的 JWT 一律 401（登出/改密时推进）。 */
    private LocalDateTime tokenInvalidBefore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long deleted;
    private Long orgId;
}
