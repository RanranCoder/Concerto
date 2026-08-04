package com.pams.module.activity.dto;

import lombok.Data;

@Data
public class SigninSummaryVO {
    private long expected;
    private long signed;
    private long unsigned;
}
