package com.chris64233.cc.waterrights.permit;

import com.chris64233.cc.waterrights.error.BusinessException;
import com.chris64233.cc.waterrights.permit.dto.CreatePermitRequest;
import com.chris64233.cc.waterrights.permit.dto.DeclarationRequest;
import com.chris64233.cc.waterrights.permit.dto.EventResponse;
import com.chris64233.cc.waterrights.permit.dto.LedgerResponse;
import com.chris64233.cc.waterrights.permit.dto.PermitResponse;
import com.chris64233.cc.waterrights.permit.dto.ReversalRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PermitService {

    private final PermitRepository permitRepository;
    private final WaterEventRepository eventRepository;

    public PermitService(PermitRepository permitRepository, WaterEventRepository eventRepository) {
        this.permitRepository = permitRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public PermitResponse createPermit(CreatePermitRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PERMIT_PERIOD_INVALID", "许可起始日期不能晚于截止日期");
        }
        if (permitRepository.existsByPermitNo(request.permitNo())) {
            throw duplicatePermit(request.permitNo());
        }
        Permit permit = new Permit(
                request.permitNo(),
                request.holder(),
                request.intakePoint(),
                request.startDate(),
                request.endDate(),
                request.approvedVolume());
        try {
            permitRepository.saveAndFlush(permit);
        } catch (DataIntegrityViolationException e) {
            // 并发创建同一许可号时由唯一约束兜底
            throw duplicatePermit(request.permitNo());
        }
        return PermitResponse.from(permit);
    }

    @Transactional
    public EventResponse declare(String permitNo, DeclarationRequest request) {
        var existing = eventRepository.findByEventNo(request.eventNo());
        if (existing.isPresent()) {
            return replayDeclaration(existing.get(), permitNo, request);
        }
        Permit permit = lockPermit(permitNo);
        // 加锁后复查，避免并发下同一事件号重复入账
        existing = eventRepository.findByEventNo(request.eventNo());
        if (existing.isPresent()) {
            return replayDeclaration(existing.get(), permitNo, request);
        }
        if (request.occurredDate().isBefore(permit.getStartDate())
                || request.occurredDate().isAfter(permit.getEndDate())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EVENT_OUT_OF_PERIOD", "申报日期不在许可有效期内");
        }
        BigDecimal newUsed = permit.getUsedVolume().add(request.volume());
        if (newUsed.compareTo(permit.getApprovedVolume()) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "QUOTA_EXCEEDED", "累计有效用水将超过核准水量");
        }
        permit.addUsedVolume(request.volume());
        WaterEvent event = WaterEvent.declaration(permit, request.eventNo(), request.occurredDate(), request.volume());
        eventRepository.save(event);
        return EventResponse.from(event);
    }

    @Transactional
    public EventResponse reverse(String permitNo, ReversalRequest request) {
        var existing = eventRepository.findByEventNo(request.eventNo());
        if (existing.isPresent()) {
            return replayReversal(existing.get(), permitNo, request);
        }
        Permit permit = lockPermit(permitNo);
        existing = eventRepository.findByEventNo(request.eventNo());
        if (existing.isPresent()) {
            return replayReversal(existing.get(), permitNo, request);
        }
        WaterEvent target = eventRepository.findByEventNo(request.declarationEventNo())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "DECLARATION_NOT_FOUND", "被冲正的申报不存在: " + request.declarationEventNo()));
        if (target.getType() != WaterEventType.DECLARATION) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "REVERSAL_NOT_ALLOWED", "冲正事件不可再次冲正");
        }
        if (!target.getPermit().getId().equals(permit.getId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "DECLARATION_NOT_FOUND", "被冲正的申报不属于该许可");
        }
        if (eventRepository.findByReversesEvent_Id(target.getId()).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "ALREADY_REVERSED", "该申报已被冲正，不能重复冲正");
        }
        permit.subtractUsedVolume(target.getVolume());
        WaterEvent reversal = WaterEvent.reversal(permit, request.eventNo(), request.occurredDate(), target);
        eventRepository.save(reversal);
        return EventResponse.from(reversal);
    }

    @Transactional(readOnly = true)
    public LedgerResponse ledger(String permitNo) {
        Permit permit = permitRepository.findByPermitNo(permitNo)
                .orElseThrow(() -> permitNotFound(permitNo));
        List<WaterEvent> events = eventRepository.findByPermit_IdOrderByIdAsc(permit.getId());
        BigDecimal declared = sumByType(events, WaterEventType.DECLARATION);
        BigDecimal reversed = sumByType(events, WaterEventType.REVERSAL);
        BigDecimal effective = declared.subtract(reversed);
        BigDecimal remaining = permit.getApprovedVolume().subtract(effective);
        return new LedgerResponse(
                permit.getPermitNo(),
                permit.getHolder(),
                permit.getIntakePoint(),
                permit.getStartDate(),
                permit.getEndDate(),
                permit.getApprovedVolume(),
                effective,
                reversed,
                remaining,
                events.stream().map(EventResponse::from).toList());
    }

    private Permit lockPermit(String permitNo) {
        return permitRepository.findByPermitNoForUpdate(permitNo)
                .orElseThrow(() -> permitNotFound(permitNo));
    }

    /** 幂等重放：事件号已存在时，内容完全一致返回原结果，否则冲突。 */
    private EventResponse replayDeclaration(WaterEvent event, String permitNo, DeclarationRequest request) {
        boolean same = event.getType() == WaterEventType.DECLARATION
                && event.getPermit().getPermitNo().equals(permitNo)
                && event.getOccurredDate().equals(request.occurredDate())
                && event.getVolume().compareTo(request.volume()) == 0;
        if (!same) {
            throw eventConflict(request.eventNo());
        }
        return EventResponse.from(event);
    }

    private EventResponse replayReversal(WaterEvent event, String permitNo, ReversalRequest request) {
        boolean same = event.getType() == WaterEventType.REVERSAL
                && event.getPermit().getPermitNo().equals(permitNo)
                && event.getOccurredDate().equals(request.occurredDate())
                && event.getReversesEvent() != null
                && event.getReversesEvent().getEventNo().equals(request.declarationEventNo());
        if (!same) {
            throw eventConflict(request.eventNo());
        }
        return EventResponse.from(event);
    }

    private static BigDecimal sumByType(List<WaterEvent> events, WaterEventType type) {
        return events.stream()
                .filter(event -> event.getType() == type)
                .map(WaterEvent::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BusinessException permitNotFound(String permitNo) {
        return new BusinessException(HttpStatus.NOT_FOUND,
                "PERMIT_NOT_FOUND", "许可不存在: " + permitNo);
    }

    private static BusinessException duplicatePermit(String permitNo) {
        return new BusinessException(HttpStatus.CONFLICT,
                "PERMIT_NO_DUPLICATE", "许可号已存在: " + permitNo);
    }

    private static BusinessException eventConflict(String eventNo) {
        return new BusinessException(HttpStatus.CONFLICT,
                "EVENT_CONFLICT", "事件号已存在且内容不一致: " + eventNo);
    }
}
