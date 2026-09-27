package com.chris64233.cc.waterrights.domain;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 不可变台账事件：用水申报或冲正。
 *
 * <p>事件一经创建不可修改、不可删除；冲正通过新增一条 REVERSAL 事件实现，
 * 并把原申报标记为 {@code reversed=true}。冲正事件本身不能再被冲正。
 */
@Entity
@Table(name = "usage_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_external_event_no", columnNames = "external_event_no"))
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 外部事件号，全局唯一，是申报/冲正的幂等键。 */
    @Column(name = "external_event_no", nullable = false, length = 64)
    private String externalEventNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_id", nullable = false)
    private WaterPermit permit;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private EventType type;

    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;

    /** 事件水量，固定保留 3 位小数，始终为正；冲正的抵消方向由类型表达。 */
    @Column(name = "volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal volume;

    /** 冲正事件指向的原申报；申报事件为 null。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_event_id")
    private UsageEvent originalEvent;

    /** 申报是否已被冲正。 */
    @Column(name = "reversed", nullable = false)
    private boolean reversed = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UsageEvent() {
    }

    public UsageEvent(String externalEventNo, WaterPermit permit, EventType type,
                      LocalDate occurrenceDate, BigDecimal volume, UsageEvent originalEvent,
                      Instant createdAt) {
        this.externalEventNo = externalEventNo;
        this.permit = permit;
        this.type = type;
        this.occurrenceDate = occurrenceDate;
        this.volume = volume;
        this.originalEvent = originalEvent;
        this.createdAt = createdAt;
    }

    public void markReversed() {
        this.reversed = true;
    }

    public Long getId() {
        return id;
    }

    public String getExternalEventNo() {
        return externalEventNo;
    }

    public WaterPermit getPermit() {
        return permit;
    }

    public EventType getType() {
        return type;
    }

    public LocalDate getOccurrenceDate() {
        return occurrenceDate;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public UsageEvent getOriginalEvent() {
        return originalEvent;
    }

    public boolean isReversed() {
        return reversed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
