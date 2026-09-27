package com.chris64233.cc.waterrights.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreatePermitRequest(
        @NotBlank(message = "许可号不能为空")
        @Size(max = 64, message = "许可号长度不能超过64")
        String permitNo,

        @NotBlank(message = "权利人不能为空")
        @Size(max = 128, message = "权利人长度不能超过128")
        String owner,

        @NotBlank(message = "取水点不能为空")
        @Size(max = 128, message = "取水点长度不能超过128")
        String intakePoint,

        @NotNull(message = "起始日期不能为空")
        LocalDate startDate,

        @NotNull(message = "截止日期不能为空")
        LocalDate endDate,

        @NotNull(message = "核准水量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "核准水量必须大于零")
        BigDecimal authorizedVolume) {
}
