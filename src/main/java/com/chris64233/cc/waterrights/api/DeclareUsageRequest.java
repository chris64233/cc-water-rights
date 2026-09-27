package com.chris64233.cc.waterrights.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record DeclareUsageRequest(
        @NotBlank(message = "外部事件号不能为空")
        @Size(max = 64, message = "外部事件号长度不能超过64")
        String externalEventNo,

        @NotNull(message = "发生日期不能为空")
        LocalDate occurrenceDate,

        @NotNull(message = "水量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "水量必须大于零")
        BigDecimal volume) {
}
