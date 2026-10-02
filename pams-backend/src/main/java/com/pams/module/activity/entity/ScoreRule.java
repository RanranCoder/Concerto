package com.pams.module.activity.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "score_rule")
public class ScoreRule implements OrgScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private String dimensionName;
    private Integer fullMarks;
    private Integer sortOrder;
    private Long orgId;
}
