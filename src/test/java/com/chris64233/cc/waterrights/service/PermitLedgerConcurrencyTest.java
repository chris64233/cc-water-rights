package com.chris64233.cc.waterrights.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.waterrights.api.CreatePermitRequest;
import com.chris64233.cc.waterrights.api.CreateReversalRequest;
import com.chris64233.cc.waterrights.api.DeclareUsageRequest;
import com.chris64233.cc.waterrights.api.EventResponse;
import com.chris64233.cc.waterrights.api.PermitResponse;
import com.chris64233.cc.waterrights.domain.EventType;
import com.chris64233.cc.waterrights.error.ApiException;
import com.chris64233.cc.waterrights.error.ErrorCodes;
import com.chris64233.cc.waterrights.repository.UsageEventRepository;
import com.chris64233.cc.waterrights.repository.WaterPermitRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发一致性测试：悲观行锁串行化同一许可上的额度变更。
 */
@SpringBootTest
class PermitLedgerConcurrencyTest {

    private static final LocalDate START = LocalDate.of(2026, 4, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Autowired
    private PermitLedgerService service;
    @Autowired
    private WaterPermitRepository permitRepository;
    @Autowired
    private UsageEventRepository eventRepository;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAllInBatch();
        permitRepository.deleteAllInBatch();
        pool = Executors.newFixedThreadPool(16);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private void createPermit(String no, String volume) {
        service.createPermit(new CreatePermitRequest(
                no, "张三", "一号口", START, END, new BigDecimal(volume)));
    }

    private void await(List<Future<?>> futures) throws Exception {
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentDeclarations_neverExceedQuota() throws Exception {
        createPermit("P-CQ", "100");
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Map<Integer, String> outcomes = new ConcurrentHashMap<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.declare("P-CQ", new DeclareUsageRequest(
                            "CE-" + idx, LocalDate.of(2026, 4, 1), new BigDecimal("30")));
                    outcomes.put(idx, "SUCCESS");
                } catch (ApiException ex) {
                    outcomes.put(idx, ex.getCode());
                } catch (Exception ex) {
                    outcomes.put(idx, "ERROR:" + ex.getClass().getSimpleName());
                }
                return null;
            }));
        }
        ready.await();
        start.countDown();
        await(futures);

        long success = outcomes.values().stream().filter("SUCCESS"::equals).count();
        long quotaErrors = outcomes.values().stream()
                .filter(ErrorCodes.QUOTA_EXCEEDED::equals).count();
        // 100 / 30 向下取整 => 恰好 3 笔成功，其余全部为额度不足，无其他异常
        assertThat(success).isEqualTo(3);
        assertThat(success + quotaErrors).isEqualTo(threads);
        assertThat(outcomes.values()).doesNotContain("ERROR");

        PermitResponse ledger = service.getLedger("P-CQ");
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("90.000");
        assertThat(ledger.remainingVolume()).isEqualByComparingTo("10.000");
        assertThat(ledger.events()).hasSize(3);
        assertLedgerInvariant(ledger);
    }

    @Test
    void concurrentDeclarationAndReversal_ledgerStaysConsistent() throws Exception {
        // 反复制造交错：已有 80 的申报，冲正(恢复80) 与 50 的新申报并发
        for (int round = 0; round < 20; round++) {
            final int r = round;
            String permitNo = "P-MIX-" + r;
            createPermit(permitNo, "100");
            service.declare(permitNo, new DeclareUsageRequest(
                    "MD-" + r, LocalDate.of(2026, 4, 1), new BigDecimal("80")));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<String> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();

            Future<?> reversal = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.reverse(permitNo, new CreateReversalRequest(
                            "MR-" + r, "MD-" + r,
                            LocalDate.of(2026, 10, 1), new BigDecimal("80")));
                    outcomes.add("REVERSAL_OK");
                } catch (Exception ex) {
                    outcomes.add("REVERSAL_FAIL:" + codeOf(ex));
                }
                return null;
            });
            Future<?> declaration = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.declare(permitNo, new DeclareUsageRequest(
                            "MN-" + r, LocalDate.of(2026, 4, 2), new BigDecimal("50")));
                    outcomes.add("DECLARE_OK");
                } catch (Exception ex) {
                    outcomes.add("DECLARE_FAIL:" + codeOf(ex));
                }
                return null;
            });
            ready.await();
            start.countDown();
            reversal.get(30, TimeUnit.SECONDS);
            declaration.get(30, TimeUnit.SECONDS);

            PermitResponse ledger = service.getLedger(permitNo);
            // 冲正必须成功（它要么先执行，要么在申报失败后执行）
            assertThat(outcomes).contains("REVERSAL_OK");
            // 台账恒等式：剩余 = 核准 - 有效用水 >= 0；有效 = 申报合计 - 冲正合计
            assertLedgerInvariant(ledger);
            // 原申报一定被标记为已冲正，已冲正量恰为 80
            assertThat(ledger.reversedVolume()).isEqualByComparingTo("80.000");
            EventResponse original = ledger.events().stream()
                    .filter(e -> e.externalEventNo().equals("MD-" + r))
                    .findFirst().orElseThrow();
            assertThat(original.reversed()).isTrue();
            // 两种合法串行化结果之一：
            if (outcomes.contains("DECLARE_OK")) {
                // 冲正先到：恢复额度后 50 的申报成功
                assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("50.000");
                assertThat(ledger.events()).hasSize(3);
            } else {
                // 申报先到：仅剩 20 额度，50 申报被拒；随后冲正
                assertThat(outcomes).contains("DECLARE_FAIL:" + ErrorCodes.QUOTA_EXCEEDED);
                assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("0.000");
                assertThat(ledger.events()).hasSize(2);
            }
        }
    }

    @Test
    void concurrentDistinctReversals_onlyOneTakesEffect() throws Exception {
        createPermit("P-CR", "100");
        service.declare("P-CR", new DeclareUsageRequest(
                "CD-1", LocalDate.of(2026, 4, 1), new BigDecimal("40")));

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Map<Integer, String> outcomes = new ConcurrentHashMap<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.reverse("P-CR", new CreateReversalRequest(
                            "CR-" + idx, "CD-1",
                            LocalDate.of(2026, 10, 1), new BigDecimal("40")));
                    outcomes.put(idx, "SUCCESS");
                } catch (ApiException ex) {
                    outcomes.put(idx, ex.getCode());
                } catch (Exception ex) {
                    outcomes.put(idx, "ERROR:" + ex.getClass().getSimpleName());
                }
                return null;
            }));
        }
        ready.await();
        start.countDown();
        await(futures);

        long success = outcomes.values().stream().filter("SUCCESS"::equals).count();
        long rejected = outcomes.values().stream()
                .filter(ErrorCodes.DECLARATION_ALREADY_REVERSED::equals).count();
        assertThat(success).isEqualTo(1);
        assertThat(success + rejected).isEqualTo(threads);

        PermitResponse ledger = service.getLedger("P-CR");
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.reversedVolume()).isEqualByComparingTo("40.000");
        assertThat(ledger.remainingVolume()).isEqualByComparingTo("100.000");
        assertThat(ledger.events()).hasSize(2);
        assertLedgerInvariant(ledger);
    }

    /** 从事件列表重算余额并与许可汇总比对，校验台账自洽。 */
    private void assertLedgerInvariant(PermitResponse ledger) {
        BigDecimal declared = ledger.events().stream()
                .filter(e -> e.type() == EventType.DECLARATION)
                .map(EventResponse::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reversed = ledger.events().stream()
                .filter(e -> e.type() == EventType.REVERSAL)
                .map(EventResponse::volume).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(ledger.reversedVolume()).isEqualByComparingTo(reversed);
        assertThat(ledger.effectiveUsedVolume()).isEqualByComparingTo(declared.subtract(reversed));
        assertThat(ledger.remainingVolume())
                .isEqualByComparingTo(ledger.authorizedVolume().subtract(ledger.effectiveUsedVolume()));
        assertThat(ledger.remainingVolume().compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);

        // 每个冲正都指向一条已标记 reversed 的申报，且一一对应
        Map<String, EventResponse> byNo = new java.util.HashMap<>();
        ledger.events().forEach(e -> byNo.put(e.externalEventNo(), e));
        ledger.events().stream()
                .filter(e -> e.type() == EventType.REVERSAL)
                .forEach(r -> {
                    EventResponse original = byNo.get(r.originalEventNo());
                    assertThat(original).isNotNull();
                    assertThat(original.type()).isEqualTo(EventType.DECLARATION);
                    assertThat(original.reversed()).isTrue();
                    assertThat(r.volume()).isEqualByComparingTo(original.volume());
                });
    }

    private static String codeOf(Exception ex) {
        return ex instanceof ApiException apiEx ? apiEx.getCode() : ex.getClass().getSimpleName();
    }
}
