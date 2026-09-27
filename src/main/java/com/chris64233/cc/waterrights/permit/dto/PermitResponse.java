package com.chris64233.cc.waterrights.permit.dto;

import com.chris64233.cc.waterrights.permit.Permit;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PermitResponse(
        String permitNo,
        String holder,
        String intakePoint,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal approvedVolume) {

    public static PermitResponse from(Permit permit) {
        return new PermitResponse(
                permit.getPermitNo(),
                permit.getHolder(),
                permit.getIntakePoint(),
                permit.getStartDate(),
                permit.getEndDate(),
                permit.getApprovedVolume());
    }
}
