package com.pams.module.activity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SigninFieldConfigRequest {
    private String fieldName;
    private String fieldKey;
    private Boolean required;
    private String fieldType;
    private Integer sortOrder;
}
