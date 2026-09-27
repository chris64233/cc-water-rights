package com.chris64233.cc.waterrights.permit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ReversalRequest(
        @NotBlank @Size(max = 64) String eventNo,
        @NotNull LocalDate occurredDate,
        @NotBlank @Size(max = 64) String declarationEventNo) {
}
