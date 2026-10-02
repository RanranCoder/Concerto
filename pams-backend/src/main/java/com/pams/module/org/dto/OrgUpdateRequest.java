package com.pams.module.org.dto;

import lombok.Data;

import java.util.List;

/**
 * 修改当前组织信息。
 */
@Data
public class OrgUpdateRequest {
    private String name;
    private String shortName;
    private String logoUrl;
    private String themeColor;
    private List<String> enabledPacks;
}
