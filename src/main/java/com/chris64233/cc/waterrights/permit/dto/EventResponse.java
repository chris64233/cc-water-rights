package com.chris64233.cc.waterrights.permit.dto;

import com.chris64233.cc.waterrights.permit.WaterEvent;
import com.chris64233.cc.waterrights.permit.WaterEventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record EventResponse(
        String eventNo,
        String permitNo,
        WaterEventType type,
        LocalDate occurredDate,
        BigDecimal volume,
        String declarationEventNo,
        Instant createdAt) {

    public static EventResponse from(WaterEvent event) {
        return new EventResponse(
                event.getEventNo(),
                event.getPermit().getPermitNo(),
                event.getType(),
                event.getOccurredDate(),
                event.getVolume(),
                event.getReversesEvent() == null ? null : event.getReversesEvent().getEventNo(),
                event.getCreatedAt());
    }
}
