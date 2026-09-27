package com.chris64233.cc.waterrights.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 许可台账：核准量 / 有效用水 / 已冲正量 / 剩余额度 + 不可变事件列表。
 */
public record PermitResponse(
        String permitNo,
        String owner,
        String intakePoint,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal authorizedVolume,
        BigDecimal effectiveUsedVolume,
        BigDecimal reversedVolume,
        BigDecimal remainingVolume,
        List<EventResponse> events) {
}
