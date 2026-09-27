package com.chris64233.cc.waterrights.api;

import com.chris64233.cc.waterrights.domain.EventType;
import com.chris64233.cc.waterrights.domain.UsageEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 不可变台账事件视图。
 *
 * @param originalEventNo 冲正事件指向的原申报外部事件号；申报事件为 null
 * @param reversed        申报是否已被冲正；冲正事件恒为 false
 */
public record EventResponse(
        Long eventId,
        String externalEventNo,
        EventType type,
        LocalDate occurrenceDate,
        BigDecimal volume,
        boolean reversed,
        String originalEventNo,
        Instant createdAt) {

    public static EventResponse from(UsageEvent event) {
        UsageEvent original = event.getOriginalEvent();
        return new EventResponse(
                event.getId(),
                event.getExternalEventNo(),
                event.getType(),
                event.getOccurrenceDate(),
                event.getVolume(),
                event.isReversed(),
                original == null ? null : original.getExternalEventNo(),
                event.getCreatedAt());
    }
}
