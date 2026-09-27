package com.chris64233.cc.waterrights.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 季节取水许可：固定精度核准水量与台账余额。
 *
 * <p>余额字段与审计事件在同一事务内、同一许可行锁下更新：
 * <ul>
 *     <li>{@code effectiveUsedVolume}：累计有效用水（未被冲正的申报量）</li>
 *     <li>{@code reversedVolume}：累计已冲正量</li>
 * </ul>
 */
@Entity
@Table(name = "water_permit",
        uniqueConstraints = @UniqueConstraint(name = "uk_permit_no", columnNames = "permit_no"))
public class WaterPermit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permit_no", nullable = false, length = 64)
    private String permitNo;

    @Column(name = "owner_name", nullable = false, length = 128)
    private String owner;

    @Column(name = "intake_point", nullable = false, length = 128)
    private String intakePoint;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "authorized_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal authorizedVolume;

    @Column(name = "effective_used_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal effectiveUsedVolume = BigDecimal.ZERO.setScale(3);

    @Column(name = "reversed_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal reversedVolume = BigDecimal.ZERO.setScale(3);

    @Version
    @Column(name = "version")
    private Long version;

    protected WaterPermit() {
    }

    public WaterPermit(String permitNo, String owner, String intakePoint,
                       LocalDate startDate, LocalDate endDate, BigDecimal authorizedVolume) {
        this.permitNo = permitNo;
        this.owner = owner;
        this.intakePoint = intakePoint;
        this.startDate = startDate;
        this.endDate = endDate;
        this.authorizedVolume = authorizedVolume;
    }

    /** 登记一次有效申报：累加有效用水，返回新的剩余额度。 */
    public BigDecimal applyDeclaration(BigDecimal volume) {
        this.effectiveUsedVolume = effectiveUsedVolume.add(volume);
        return remainingVolume();
    }

    /** 登记一次冲正：扣减有效用水、累加已冲正量。 */
    public void applyReversal(BigDecimal volume) {
        this.effectiveUsedVolume = effectiveUsedVolume.subtract(volume);
        this.reversedVolume = reversedVolume.add(volume);
    }

    public BigDecimal remainingVolume() {
        return authorizedVolume.subtract(effectiveUsedVolume);
    }

    public Long getId() {
        return id;
    }

    public String getPermitNo() {
        return permitNo;
    }

    public String getOwner() {
        return owner;
    }

    public String getIntakePoint() {
        return intakePoint;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BigDecimal getAuthorizedVolume() {
        return authorizedVolume;
    }

    public BigDecimal getEffectiveUsedVolume() {
        return effectiveUsedVolume;
    }

    public BigDecimal getReversedVolume() {
        return reversedVolume;
    }

    public Long getVersion() {
        return version;
    }
}
