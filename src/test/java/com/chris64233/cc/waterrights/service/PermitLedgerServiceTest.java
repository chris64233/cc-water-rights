package com.chris64233.cc.waterrights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.cc.waterrights.api.CreatePermitRequest;
import com.chris64233.cc.waterrights.api.CreateReversalRequest;
import com.chris64233.cc.waterrights.api.DeclareUsageRequest;
import com.chris64233.cc.waterrights.api.EventResult;
import com.chris64233.cc.waterrights.api.PermitResponse;
import com.chris64233.cc.waterrights.domain.EventType;
import com.chris64233.cc.waterrights.error.ApiException;
import com.chris64233.cc.waterrights.error.ErrorCodes;
import com.chris64233.cc.waterrights.repository.UsageEventRepository;
import com.chris64233.cc.waterrights.repository.WaterPermitRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

@SpringBootTest
class PermitLedgerServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 4, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Autowired
    private PermitLedgerService service;
    @Autowired
    private WaterPermitRepository permitRepository;
    @Autowired
    private UsageEventRepository eventRepository;

    @BeforeEach
    void clean() {
        eventRepository.deleteAllInBatch();
        permitRepository.deleteAllInBatch();
    }

    private CreatePermitRequest permitRequest(String no, String volume) {
        return new CreatePermitRequest(no, "张三", "一号取水口", START, END, new BigDecimal(volume));
    }

    private DeclareUsageRequest declaration(String eventNo, String date, String volume) {
        return new DeclareUsageRequest(eventNo, LocalDate.parse(date), new BigDecimal(volume));
    }

    // ---------- 许可创建 ----------

    @Test
    void createPermit_persistsFixedScaleZeroBalances() {
        PermitResponse response = service.createPermit(permitRequest("P-001", "100.000"));

        assertThat(response.permitNo()).isEqualTo("P-001");
        assertThat(response.owner()).isEqualTo("张三");
        assertThat(response.intakePoint()).isEqualTo("一号取水口");
        assertThat(response.startDate()).isEqualTo(START);
        assertThat(response.endDate()).isEqualTo(END);
        assertThat(response.authorizedVolume()).isEqualByComparingTo("100.000");
        assertThat(response.effectiveUsedVolume()).isEqualByComparingTo("0.000");
        assertThat(response.reversedVolume()).isEqualByComparingTo("0.000");
        assertThat(response.remainingVolume()).isEqualByComparingTo("100.000");
        assertThat(response.events()).isEmpty();
    }

    @Test
    void createPermit_normalizesVolumeToFixedScale() {
        PermitResponse response = service.createPermit(permitRequest("P-SCALE", "100.1255"));
        assertThat(response.authorizedVolume().scale()).isEqualTo(3);
        assertThat(response.authorizedVolume()).isEqualByComparingTo("100.126");
    }

    @Test
    void createPermit_duplicatePermitNo_conflict() {
        service.createPermit(permitRequest("P-DUP", "100"));

        assertThatThrownBy(() -> service.createPermit(permitRequest("P-DUP", "200")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.PERMIT_NO_DUPLICATED);
                });
    }

    @Test
    void createPermit_endBeforeStart_rejected() {
        CreatePermitRequest request = new CreatePermitRequest(
                "P-DATES", "张三", "口", END, START, new BigDecimal("100"));

        assertThatThrownBy(() -> service.createPermit(request))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.INVALID_DATE_RANGE));
    }

    // ---------- 申报 ----------

    @Test
    void declare_accumulatesAndReducesRemaining() {
        service.createPermit(permitRequest("P-USE", "100"));

        EventResult r1 = service.declare("P-USE", declaration("E-1", "2026-04-10", "30.5"));
        EventResult r2 = service.declare("P-USE", declaration("E-2", "2026-09-30", "20"));

        assertThat(r1.replayed()).isFalse();
        assertThat(r1.event().type()).isEqualTo(EventType.DECLARATION);
        assertThat(r2.event().volume()).isEqualByComparingTo("20.000");

        PermitResponse ledger = service.getLedger("P-USE");
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("50.500");
        assertThat(ledger.remainingVolume()).isEqualByComparingTo("49.500");
        assertThat(ledger.reversedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.events()).hasSize(2);
    }

    @Test
    void declare_dateBeforeStart_rejected() {
        service.createPermit(permitRequest("P-RANGE", "100"));

        assertThatThrownBy(() -> service.declare("P-RANGE", declaration("E-X", "2026-03-31", "1")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.EVENT_DATE_OUT_OF_RANGE));
    }

    @Test
    void declare_dateAfterEnd_rejected() {
        service.createPermit(permitRequest("P-RANGE2", "100"));

        assertThatThrownBy(() -> service.declare("P-RANGE2", declaration("E-Y", "2026-10-01", "1")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.EVENT_DATE_OUT_OF_RANGE));
    }

    @Test
    void declare_exceedingQuota_rejectedAndDoesNotConsumeQuota() {
        service.createPermit(permitRequest("P-Q", "100"));
        service.declare("P-Q", declaration("E-A", "2026-04-01", "80"));

        assertThatThrownBy(() -> service.declare("P-Q", declaration("E-B", "2026-04-02", "20.001")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.QUOTA_EXCEEDED));

        PermitResponse ledger = service.getLedger("P-Q");
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("80.000");
        assertThat(ledger.remainingVolume()).isEqualByComparingTo("20.000");
        assertThat(ledger.events()).hasSize(1);
    }

    @Test
    void declare_remainingExactlyEqual_succeeds() {
        service.createPermit(permitRequest("P-EQ", "100"));
        service.declare("P-EQ", declaration("E-A", "2026-04-01", "60"));

        EventResult result = service.declare("P-EQ", declaration("E-B", "2026-04-02", "40"));

        assertThat(result.replayed()).isFalse();
        assertThat(service.getLedger("P-EQ").remainingVolume()).isEqualByComparingTo("0.000");
    }

    @Test
    void declare_unknownPermit_notFound() {
        assertThatThrownBy(() -> service.declare("NOPE", declaration("E-1", "2026-04-01", "1")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.PERMIT_NOT_FOUND);
                });
    }

    // ---------- 幂等 ----------

    @Test
    void declare_sameEventNoSameContent_replaysOriginal() {
        service.createPermit(permitRequest("P-IDEM", "100"));
        EventResult first = service.declare("P-IDEM", declaration("EV-1", "2026-04-01", "10"));

        EventResult replay = service.declare("P-IDEM", declaration("EV-1", "2026-04-01", "10"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.event().eventId()).isEqualTo(first.event().eventId());
        // 重放不重复扣额度，也不新增事件
        PermitResponse ledger = service.getLedger("P-IDEM");
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("10.000");
        assertThat(ledger.events()).hasSize(1);
    }

    @Test
    void declare_sameEventNoDifferentVolume_conflict() {
        service.createPermit(permitRequest("P-IDEM2", "100"));
        service.declare("P-IDEM2", declaration("EV-2", "2026-04-01", "10"));

        assertThatThrownBy(() -> service.declare("P-IDEM2", declaration("EV-2", "2026-04-01", "11")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_CONTENT_CONFLICT);
                });
    }

    @Test
    void declare_sameEventNoDifferentDate_conflict() {
        service.createPermit(permitRequest("P-IDEM3", "100"));
        service.declare("P-IDEM3", declaration("EV-3", "2026-04-01", "10"));

        assertThatThrownBy(() -> service.declare("P-IDEM3", declaration("EV-3", "2026-04-02", "10")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void declare_replayStillReturnsOriginalEvenWhenQuotaNowFull() {
        service.createPermit(permitRequest("P-IDEM4", "100"));
        service.declare("P-IDEM4", declaration("EV-4", "2026-04-01", "100"));
        // 新申报会超额，但同内容重放仍返回原结果
        EventResult replay = service.declare("P-IDEM4", declaration("EV-4", "2026-04-01", "100"));
        assertThat(replay.replayed()).isTrue();
        assertThat(service.getLedger("P-IDEM4").events()).hasSize(1);
    }

    @Test
    void declare_eventNoReusedOnAnotherPermit_conflict() {
        service.createPermit(permitRequest("P-A", "100"));
        service.createPermit(permitRequest("P-B", "100"));
        service.declare("P-A", declaration("SHARED-E", "2026-04-01", "10"));

        assertThatThrownBy(() -> service.declare("P-B", declaration("SHARED-E", "2026-04-01", "10")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_CONTENT_CONFLICT));
    }

    // ---------- 冲正 ----------

    @Test
    void reverse_offsetsDeclarationAndRestoresQuota() {
        service.createPermit(permitRequest("P-REV", "100"));
        service.declare("P-REV", declaration("D-1", "2026-04-01", "60"));
        // 额度仅剩 40，冲正后恢复 60，又可申报 90
        assertThatThrownBy(() -> service.declare("P-REV", declaration("D-2", "2026-04-03", "50")))
                .isInstanceOf(ApiException.class);

        EventResult reversal = service.reverse("P-REV",
                new CreateReversalRequest("R-1", "D-1", LocalDate.of(2026, 10, 5), new BigDecimal("60")));

        assertThat(reversal.replayed()).isFalse();
        assertThat(reversal.event().type()).isEqualTo(EventType.REVERSAL);
        assertThat(reversal.event().originalEventNo()).isEqualTo("D-1");

        PermitResponse ledger = service.getLedger("P-REV");
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.reversedVolume()).isEqualByComparingTo("60.000");
        assertThat(ledger.remainingVolume()).isEqualByComparingTo("100.000");

        service.declare("P-REV", declaration("D-3", "2026-04-03", "90"));
        assertThat(service.getLedger("P-REV").effectiveUsedVolume()).isEqualByComparingTo("90.000");
    }

    @Test
    void reverse_partialOffsetRejected_volumeMustMatchExactly() {
        service.createPermit(permitRequest("P-RV", "100"));
        service.declare("P-RV", declaration("D-1", "2026-04-01", "60"));

        assertThatThrownBy(() -> service.reverse("P-RV",
                new CreateReversalRequest("R-X", "D-1", LocalDate.of(2026, 10, 1), new BigDecimal("59.999"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.REVERSAL_VOLUME_MISMATCH));

        PermitResponse ledger = service.getLedger("P-RV");
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("60.000");
        assertThat(ledger.reversedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.events()).hasSize(1);
    }

    @Test
    void reverse_unknownOriginalEvent_notFound() {
        service.createPermit(permitRequest("P-RN", "100"));

        assertThatThrownBy(() -> service.reverse("P-RN",
                new CreateReversalRequest("R-1", "GHOST", LocalDate.of(2026, 10, 1), new BigDecimal("1"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.EVENT_NOT_FOUND));
    }

    @Test
    void reverse_originalEventOnAnotherPermit_notFound() {
        service.createPermit(permitRequest("P-X1", "100"));
        service.createPermit(permitRequest("P-X2", "100"));
        service.declare("P-X1", declaration("D-X1", "2026-04-01", "10"));

        assertThatThrownBy(() -> service.reverse("P-X2",
                new CreateReversalRequest("R-X1", "D-X1", LocalDate.of(2026, 10, 1), new BigDecimal("10"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.EVENT_NOT_FOUND));
    }

    @Test
    void reverse_reversalCannotBeReversed() {
        service.createPermit(permitRequest("P-RR", "100"));
        service.declare("P-RR", declaration("D-1", "2026-04-01", "10"));
        service.reverse("P-RR",
                new CreateReversalRequest("R-1", "D-1", LocalDate.of(2026, 10, 1), new BigDecimal("10")));

        assertThatThrownBy(() -> service.reverse("P-RR",
                new CreateReversalRequest("R-2", "R-1", LocalDate.of(2026, 10, 2), new BigDecimal("10"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.REVERSAL_NOT_REVERSIBLE));
    }

    @Test
    void reverse_alreadyReversedSameRequest_replaysOriginal() {
        service.createPermit(permitRequest("P-DR", "100"));
        service.declare("P-DR", declaration("D-1", "2026-04-01", "10"));
        EventResult first = service.reverse("P-DR",
                new CreateReversalRequest("R-1", "D-1", LocalDate.of(2026, 10, 1), new BigDecimal("10")));

        // 相同冲正请求重放 -> 原结果
        EventResult replay = service.reverse("P-DR",
                new CreateReversalRequest("R-1", "D-1", LocalDate.of(2026, 10, 1), new BigDecimal("10")));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.event().eventId()).isEqualTo(first.event().eventId());

        PermitResponse ledger = service.getLedger("P-DR");
        assertThat(ledger.events()).hasSize(2);
        assertThat(ledger.reversedVolume()).isEqualByComparingTo("10.000");
    }

    @Test
    void reverse_alreadyReversedDifferentRequest_conflict() {
        service.createPermit(permitRequest("P-DR2", "100"));
        service.declare("P-DR2", declaration("D-1", "2026-04-01", "10"));
        service.reverse("P-DR2",
                new CreateReversalRequest("R-1", "D-1", LocalDate.of(2026, 10, 1), new BigDecimal("10")));

        // 不同冲正事件号、指向同一已冲正申报 -> 冲突
        assertThatThrownBy(() -> service.reverse("P-DR2",
                new CreateReversalRequest("R-2", "D-1", LocalDate.of(2026, 10, 1), new BigDecimal("10"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.DECLARATION_ALREADY_REVERSED);
                });
    }

    @Test
    void reverse_sameEventNoDifferentContent_conflict() {
        service.createPermit(permitRequest("P-RC", "100"));
        service.declare("P-RC", declaration("D-1", "2026-04-01", "10"));
        service.declare("P-RC", declaration("D-2", "2026-04-02", "10"));
        service.reverse("P-RC",
                new CreateReversalRequest("R-1", "D-1", LocalDate.of(2026, 10, 1), new BigDecimal("10")));

        // 同一冲正事件号但指向不同原申报 -> 幂等内容冲突
        assertThatThrownBy(() -> service.reverse("P-RC",
                new CreateReversalRequest("R-1", "D-2", LocalDate.of(2026, 10, 1), new BigDecimal("10"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_CONTENT_CONFLICT));
    }

    // ---------- 台账 ----------

    @Test
    void ledger_eventsAreImmutableAndOrdered() {
        service.createPermit(permitRequest("P-LED", "100"));
        service.declare("P-LED", declaration("D-1", "2026-04-01", "40"));
        service.declare("P-LED", declaration("D-2", "2026-05-01", "35"));
        service.reverse("P-LED",
                new CreateReversalRequest("R-1", "D-1", LocalDate.of(2026, 10, 1), new BigDecimal("40")));

        PermitResponse ledger = service.getLedger("P-LED");

        assertThat(ledger.authorizedVolume()).isEqualByComparingTo("100.000");
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("35.000");
        assertThat(ledger.reversedVolume()).isEqualByComparingTo("40.000");
        assertThat(ledger.remainingVolume()).isEqualByComparingTo("65.000");
        assertThat(ledger.events()).extracting("externalEventNo")
                .containsExactly("D-1", "D-2", "R-1");
        assertThat(ledger.events().get(0).reversed()).isTrue();
        assertThat(ledger.events().get(1).reversed()).isFalse();
        assertThat(ledger.events().get(2).type()).isEqualTo(EventType.REVERSAL);
        assertThat(ledger.events().get(2).originalEventNo()).isEqualTo("D-1");
    }

    @Test
    void ledger_unknownPermit_notFound() {
        assertThatThrownBy(() -> service.getLedger("MISSING"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PERMIT_NOT_FOUND));
    }
}
