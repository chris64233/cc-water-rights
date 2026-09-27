package com.chris64233.cc.waterrights.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 冲正请求。{@code volume} 必须与原申报完全一致（完整抵消），
 * 不一致时拒绝；{@code originalEventNo} 指向被冲正的原申报外部事件号。
 */
public record CreateReversalRequest(
        @NotBlank(message = "外部事件号不能为空")
        @Size(max = 64, message = "外部事件号长度不能超过64")
        String externalEventNo,

        @NotBlank(message = "原申报事件号不能为空")
        @Size(max = 64, message = "原申报事件号长度不能超过64")
        String originalEventNo,

        @NotNull(message = "发生日期不能为空")
        LocalDate occurrenceDate,

        @NotNull(message = "水量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "水量必须大于零")
        BigDecimal volume) {
}
