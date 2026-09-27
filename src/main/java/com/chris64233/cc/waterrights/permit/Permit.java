package com.chris64233.cc.waterrights.permit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 季节取水许可。usedVolume 为当前累计有效用水（申报 - 冲正），
 * 与事件流水在同一事务内更新，并依赖许可行的悲观写锁保证并发一致。
 */
@Entity
@Table(name = "permits")
public class Permit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permit_no", nullable = false, unique = true, length = 64)
    private String permitNo;

    @Column(nullable = false, length = 128)
    private String holder;

    @Column(name = "intake_point", nullable = false, length = 128)
    private String intakePoint;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "approved_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal approvedVolume;

    @Column(name = "used_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal usedVolume = BigDecimal.ZERO;

    protected Permit() {
    }

    public Permit(String permitNo, String holder, String intakePoint,
                  LocalDate startDate, LocalDate endDate, BigDecimal approvedVolume) {
        this.permitNo = permitNo;
        this.holder = holder;
        this.intakePoint = intakePoint;
        this.startDate = startDate;
        this.endDate = endDate;
        this.approvedVolume = approvedVolume;
    }

    public Long getId() {
        return id;
    }

    public String getPermitNo() {
        return permitNo;
    }

    public String getHolder() {
        return holder;
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

    public BigDecimal getApprovedVolume() {
        return approvedVolume;
    }

    public BigDecimal getUsedVolume() {
        return usedVolume;
    }

    public void addUsedVolume(BigDecimal volume) {
        this.usedVolume = this.usedVolume.add(volume);
    }

    public void subtractUsedVolume(BigDecimal volume) {
        this.usedVolume = this.usedVolume.subtract(volume);
    }
}
