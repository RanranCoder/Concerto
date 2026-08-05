package com.pams.module.activity.dto;

import lombok.Data;

@Data
public class SignInGroupSummaryVO {
    private long total;
    private long signed;
    private long unsigned;
    private long groupCount;
}
