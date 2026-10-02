package com.pams.module.org.dto;

import lombok.Data;

/**
 * 架构节点新增/修改请求。
 */
@Data
public class UnitSaveRequest {
    private String name;
    private String unitType;
    /** 上级节点；null = 根 */
    private Long parentId;
    private Long leaderUserId;
    private Integer sortOrder;
}
