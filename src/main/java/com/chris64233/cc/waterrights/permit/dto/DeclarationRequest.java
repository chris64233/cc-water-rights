package com.chris64233.cc.waterrights.permit.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DeclarationRequest(
        @NotBlank @Size(max = 64) String eventNo,
        @NotNull LocalDate occurredDate,
        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "申报水量必须大于零")
        @Digits(integer = 16, fraction = 3, message = "水量最多 3 位小数")
        BigDecimal volume) {
}
