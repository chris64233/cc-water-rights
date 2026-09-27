package com.chris64233.cc.waterrights.permit.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record LedgerResponse(
        String permitNo,
        String holder,
        String intakePoint,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal approvedVolume,
        BigDecimal effectiveVolume,
        BigDecimal reversedVolume,
        BigDecimal remainingVolume,
        List<EventResponse> events) {
}
