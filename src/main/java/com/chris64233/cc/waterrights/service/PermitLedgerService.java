package com.chris64233.cc.waterrights.service;

import com.chris64233.cc.waterrights.api.CreatePermitRequest;
import com.chris64233.cc.waterrights.api.CreateReversalRequest;
import com.chris64233.cc.waterrights.api.DeclareUsageRequest;
import com.chris64233.cc.waterrights.api.EventResponse;
import com.chris64233.cc.waterrights.api.EventResult;
import com.chris64233.cc.waterrights.api.PermitResponse;
import com.chris64233.cc.waterrights.domain.EventType;
import com.chris64233.cc.waterrights.domain.UsageEvent;
import com.chris64233.cc.waterrights.domain.WaterPermit;
import com.chris64233.cc.waterrights.error.ApiException;
import com.chris64233.cc.waterrights.error.ErrorCodes;
import com.chris64233.cc.waterrights.repository.UsageEventRepository;
import com.chris64233.cc.waterrights.repository.WaterPermitRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 季节取水许可台账服务。
 *
 * <p>所有写入均在单一事务内完成，并通过许可行的悲观写锁串行化同一许可上的
 * 申报与冲正，保证：并发申报不会超额、申报与冲正并发后余额与审计事件一致。
 */
@Service
public class PermitLedgerService {

    /** 水量固定精度：3 位小数。 */
    public static final int VOLUME_SCALE = 3;

    private final WaterPermitRepository permitRepository;
    private final UsageEventRepository eventRepository;

    public PermitLedgerService(WaterPermitRepository permitRepository,
                               UsageEventRepository eventRepository) {
        this.permitRepository = permitRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public PermitResponse createPermit(CreatePermitRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw ApiException.unprocessable(ErrorCodes.INVALID_DATE_RANGE,
                    "许可截止日期不能早于起始日期");
        }
        if (permitRepository.existsByPermitNo(request.permitNo())) {
            throw ApiException.conflict(ErrorCodes.PERMIT_NO_DUPLICATED,
                    "许可号已存在: " + request.permitNo());
        }
        WaterPermit permit = new WaterPermit(
                request.permitNo(),
                request.owner(),
                request.intakePoint(),
                request.startDate(),
                request.endDate(),
                normalize(request.authorizedVolume()));
        try {
            permitRepository.saveAndFlush(permit);
        } catch (DataIntegrityViolationException ex) {
            // 并发创建同一许可号，唯一约束兜底
            throw ApiException.conflict(ErrorCodes.PERMIT_NO_DUPLICATED,
                    "许可号已存在: " + request.permitNo());
        }
        return toResponse(permit, List.of());
    }

    /**
     * 登记用水申报。
     */
    @Transactional
    public EventResult declare(String permitNo, DeclareUsageRequest request) {
        // 先锁定许可行，串行化同一许可上的全部额度变更，再做幂等判断
        WaterPermit permit = lockPermit(permitNo);

        UsageEvent existing = eventRepository
                .findByExternalEventNo(request.externalEventNo()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, permit, EventType.DECLARATION,
                    request.occurrenceDate(), request.volume(), null);
        }

        LocalDate date = request.occurrenceDate();
        ensureWithinValidity(permit, date);
        BigDecimal volume = normalize(request.volume());
        if (volume.compareTo(permit.remainingVolume()) > 0) {
            throw ApiException.unprocessable(ErrorCodes.QUOTA_EXCEEDED,
                    "累计有效用水将超过核准水量，剩余额度: " + permit.remainingVolume());
        }

        UsageEvent event;
        try {
            event = eventRepository.saveAndFlush(new UsageEvent(
                    request.externalEventNo(), permit, EventType.DECLARATION,
                    date, volume, null, Instant.now()));
        } catch (DataIntegrityViolationException ex) {
            // 外部事件号被其他许可上的并发事务抢先占用，属内容冲突
            throw ApiException.conflict(ErrorCodes.IDEMPOTENCY_CONTENT_CONFLICT,
                    "外部事件号已被其他事件占用: " + request.externalEventNo());
        }
        permit.applyDeclaration(volume);
        return new EventResult(EventResponse.from(event), false);
    }

    /**
     * 创建冲正事件，完整抵消原申报并恢复额度。
     */
    @Transactional
    public EventResult reverse(String permitNo, CreateReversalRequest request) {
        WaterPermit permit = lockPermit(permitNo);

        UsageEvent existing = eventRepository
                .findByExternalEventNo(request.externalEventNo()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, permit, EventType.REVERSAL,
                    request.occurrenceDate(), request.volume(), request.originalEventNo());
        }

        UsageEvent original = eventRepository
                .findByExternalEventNo(request.originalEventNo())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.EVENT_NOT_FOUND,
                        "原申报事件不存在: " + request.originalEventNo()));
        if (!original.getPermit().getId().equals(permit.getId())) {
            throw ApiException.notFound(ErrorCodes.EVENT_NOT_FOUND,
                    "原申报事件不属于该许可: " + request.originalEventNo());
        }
        if (original.getType() != EventType.DECLARATION) {
            throw ApiException.unprocessable(ErrorCodes.REVERSAL_NOT_REVERSIBLE,
                    "冲正事件不可再次冲正");
        }
        if (original.isReversed()) {
            // 原申报已被另一个冲正事件抵消：不同冲正请求冲突
            throw ApiException.conflict(ErrorCodes.DECLARATION_ALREADY_REVERSED,
                    "原申报已被冲正: " + request.originalEventNo());
        }

        // 冲正是对历史申报的纠错，发生日期不要求落在许可有效期内
        BigDecimal volume = normalize(request.volume());
        if (volume.compareTo(original.getVolume()) != 0) {
            throw ApiException.unprocessable(ErrorCodes.REVERSAL_VOLUME_MISMATCH,
                    "冲正水量必须与原申报一致，原申报水量: " + original.getVolume());
        }

        UsageEvent reversal;
        try {
            reversal = eventRepository.saveAndFlush(new UsageEvent(
                    request.externalEventNo(), permit, EventType.REVERSAL,
                    request.occurrenceDate(), volume, original, Instant.now()));
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict(ErrorCodes.IDEMPOTENCY_CONTENT_CONFLICT,
                    "外部事件号已被其他事件占用: " + request.externalEventNo());
        }
        original.markReversed();
        permit.applyReversal(volume);
        return new EventResult(EventResponse.from(reversal), false);
    }

    @Transactional(readOnly = true)
    public PermitResponse getLedger(String permitNo) {
        WaterPermit permit = permitRepository.findByPermitNo(permitNo)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.PERMIT_NOT_FOUND,
                        "许可不存在: " + permitNo));
        List<UsageEvent> events = eventRepository.findByPermit_IdOrderByIdAsc(permit.getId());
        return toResponse(permit, events.stream().map(EventResponse::from).toList());
    }

    private WaterPermit lockPermit(String permitNo) {
        return permitRepository.findByPermitNoForUpdate(permitNo)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.PERMIT_NOT_FOUND,
                        "许可不存在: " + permitNo));
    }

    /**
     * 幂等重放：既有事件与本次请求内容完全一致时返回原结果，否则冲突。
     *
     * @param expectedOriginalEventNo 冲正请求需与既有冲正指向的原申报事件号一致；申报传 null
     */
    private EventResult replayOrConflict(UsageEvent existing, WaterPermit permit,
                                         EventType expectedType, LocalDate date,
                                         BigDecimal volume, String expectedOriginalEventNo) {
        boolean sameContent = existing.getType() == expectedType
                && existing.getPermit().getId().equals(permit.getId())
                && existing.getOccurrenceDate().equals(date)
                && existing.getVolume().compareTo(normalize(volume)) == 0;
        if (sameContent && expectedType == EventType.REVERSAL) {
            UsageEvent original = existing.getOriginalEvent();
            sameContent = original != null
                    && original.getExternalEventNo().equals(expectedOriginalEventNo);
        }
        if (!sameContent) {
            throw ApiException.conflict(ErrorCodes.IDEMPOTENCY_CONTENT_CONFLICT,
                    "外部事件号已存在但请求内容不一致: " + existing.getExternalEventNo());
        }
        return new EventResult(EventResponse.from(existing), true);
    }

    private void ensureWithinValidity(WaterPermit permit, LocalDate date) {
        if (date.isBefore(permit.getStartDate()) || date.isAfter(permit.getEndDate())) {
            throw ApiException.unprocessable(ErrorCodes.EVENT_DATE_OUT_OF_RANGE,
                    "事件发生日期必须落在许可有效期 "
                            + permit.getStartDate() + " 至 " + permit.getEndDate() + " 内");
        }
    }

    private static BigDecimal normalize(BigDecimal volume) {
        return volume.setScale(VOLUME_SCALE, RoundingMode.HALF_UP);
    }

    private PermitResponse toResponse(WaterPermit permit, List<EventResponse> events) {
        return new PermitResponse(
                permit.getPermitNo(),
                permit.getOwner(),
                permit.getIntakePoint(),
                permit.getStartDate(),
                permit.getEndDate(),
                permit.getAuthorizedVolume(),
                permit.getEffectiveUsedVolume(),
                permit.getReversedVolume(),
                permit.remainingVolume(),
                events);
    }
}
