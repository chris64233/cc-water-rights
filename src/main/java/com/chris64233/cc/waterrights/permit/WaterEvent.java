package com.chris64233.cc.waterrights.permit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 不可变的用水事件流水（申报 / 冲正）。事件一旦写入不修改、不删除；
 * 错误申报只能通过追加一条冲正事件抵消。
 * event_no 全局唯一保证幂等；reverses_event_id 唯一保证一笔申报至多被冲正一次。
 */
@Entity
@Table(name = "water_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_water_events_event_no", columnNames = "event_no"),
        @UniqueConstraint(name = "uk_water_events_reverses", columnNames = "reverses_event_id")
})
public class WaterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_id", nullable = false)
    private Permit permit;

    @Column(name = "event_no", nullable = false, length = 64)
    private String eventNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WaterEventType type;

    @Column(name = "occurred_date", nullable = false)
    private LocalDate occurredDate;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal volume;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reverses_event_id")
    private WaterEvent reversesEvent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WaterEvent() {
    }

    public static WaterEvent declaration(Permit permit, String eventNo, LocalDate occurredDate, BigDecimal volume) {
        WaterEvent event = new WaterEvent();
        event.permit = permit;
        event.eventNo = eventNo;
        event.type = WaterEventType.DECLARATION;
        event.occurredDate = occurredDate;
        event.volume = volume;
        event.createdAt = Instant.now();
        return event;
    }

    /** 冲正事件：水量与被冲正的申报完全一致（完整抵消）。 */
    public static WaterEvent reversal(Permit permit, String eventNo, LocalDate occurredDate, WaterEvent target) {
        WaterEvent event = new WaterEvent();
        event.permit = permit;
        event.eventNo = eventNo;
        event.type = WaterEventType.REVERSAL;
        event.occurredDate = occurredDate;
        event.volume = target.getVolume();
        event.reversesEvent = target;
        event.createdAt = Instant.now();
        return event;
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    public String getEventNo() {
        return eventNo;
    }

    public WaterEventType getType() {
        return type;
    }

    public LocalDate getOccurredDate() {
        return occurredDate;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public WaterEvent getReversesEvent() {
        return reversesEvent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
