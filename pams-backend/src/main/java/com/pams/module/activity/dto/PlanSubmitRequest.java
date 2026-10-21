package com.pams.module.activity.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class PlanSubmitRequest {
    @NotEmpty @Size(max = 50)
    private List<@NotNull @Positive Long> reviewerIds;
}
