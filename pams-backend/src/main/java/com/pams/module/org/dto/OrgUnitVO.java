package com.pams.module.org.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 组织架构节点视图（树）。
 */
@Data
public class OrgUnitVO {
    private Long id;
    private Long parentId;
    private String name;
    private String unitType;
    private String code;
    private Integer level;
    private String path;
    private Long leaderUserId;
    private Integer sortOrder;
    private List<OrgUnitVO> children = new ArrayList<>();
}
