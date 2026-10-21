package com.pams.module.user.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record UserStatusRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> userIds,
        @NotNull @Min(0) @Max(1) Integer status) {}
