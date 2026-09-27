package com.chris64233.cc.waterrights;

import com.chris64233.cc.waterrights.error.BusinessException;
import com.chris64233.cc.waterrights.permit.PermitService;
import com.chris64233.cc.waterrights.permit.dto.CreatePermitRequest;
import com.chris64233.cc.waterrights.permit.dto.DeclarationRequest;
import com.chris64233.cc.waterrights.permit.dto.LedgerResponse;
import com.chris64233.cc.waterrights.permit.dto.ReversalRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发一致性：多个申报争抢剩余额度不得超额；申报与冲正并发时，
 * 最终余额、累计有效用水与事件流水必须自洽。
 */
@SpringBootTest
class PermitConcurrencyTests {

    private static final LocalDate START = LocalDate.of(2026, 4, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private static final LocalDate DAY = LocalDate.of(2026, 5, 1);

    @Autowired
    private PermitService permitService;

    private String newPermit(String approvedVolume) {
        String permitNo = "PC-" + UUID.randomUUID().toString().substring(0, 8);
        permitService.createPermit(new CreatePermitRequest(
                permitNo, "灌区合作社", "东风渠3号闸", START, END, new BigDecimal(approvedVolume)));
        return permitNo;
    }

    /** 所有任务在同一栅栏后同时起跑，尽量放大竞争。 */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> wrapped = tasks.stream()
                .<Callable<T>>map(task -> () -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                })
                .toList();
        List<Future<T>> futures = wrapped.stream().map(pool::submit).toList();
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    @Test
    void concurrentDeclarationsNeverExceedQuota() throws Exception {
        String permitNo = newPermit("100");
        int threads = 10;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String eventNo = "D-" + i + "-" + permitNo;
            tasks.add(() -> {
                try {
                    permitService.declare(permitNo, new DeclarationRequest(eventNo, DAY, new BigDecimal("20")));
                    return "OK";
                } catch (BusinessException e) {
                    return e.getCode();
                }
            });
        }
        List<String> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter("OK"::equals)).hasSize(5);
        assertThat(outcomes.stream().filter("QUOTA_EXCEEDED"::equals)).hasSize(5);

        LedgerResponse ledger = permitService.ledger(permitNo);
        assertThat(ledger.effectiveVolume()).isEqualByComparingTo("100");
        assertThat(ledger.remainingVolume()).isEqualByComparingTo("0");
        assertThat(ledger.events()).hasSize(5);
    }

    @Test
    void concurrentDeclarationAndReversalStayConsistent() throws Exception {
        for (int round = 0; round < 20; round++) {
            String permitNo = newPermit("100");
            String d1 = "D-1-" + permitNo;
            String d2 = "D-2-" + permitNo;
            String r1 = "R-1-" + permitNo;
            permitService.declare(permitNo, new DeclarationRequest(d1, DAY, new BigDecimal("60")));

            List<Callable<String>> tasks = List.of(
                    () -> {
                        try {
                            permitService.declare(permitNo, new DeclarationRequest(d2, DAY, new BigDecimal("50")));
                            return "D2-OK";
                        } catch (BusinessException e) {
                            return e.getCode();
                        }
                    },
                    () -> {
                        permitService.reverse(permitNo, new ReversalRequest(r1, DAY, d1));
                        return "R1-OK";
                    });
            List<String> outcomes = runConcurrently(tasks);

            assertThat(outcomes).contains("R1-OK");
            LedgerResponse ledger = permitService.ledger(permitNo);
            if (outcomes.contains("D2-OK")) {
                // 冲正先完成：额度已恢复，新申报成功
                assertThat(ledger.effectiveVolume()).isEqualByComparingTo("50");
                assertThat(ledger.remainingVolume()).isEqualByComparingTo("50");
                assertThat(ledger.reversedVolume()).isEqualByComparingTo("60");
                assertThat(ledger.events()).hasSize(3);
            } else {
                // 申报先抢到锁：60+50 超额被拒绝，随后冲正生效
                assertThat(outcomes).contains("QUOTA_EXCEEDED");
                assertThat(ledger.effectiveVolume()).isEqualByComparingTo("0");
                assertThat(ledger.remainingVolume()).isEqualByComparingTo("100");
                assertThat(ledger.reversedVolume()).isEqualByComparingTo("60");
                assertThat(ledger.events()).hasSize(2);
            }
            // 不变量：有效用水 = 申报合计 - 冲正合计，剩余 = 核准 - 有效
            BigDecimal declared = ledger.events().stream()
                    .filter(e -> e.type().name().equals("DECLARATION"))
                    .map(e -> e.volume()).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(ledger.effectiveVolume()).isEqualByComparingTo(declared.subtract(ledger.reversedVolume()));
            assertThat(ledger.remainingVolume()).isEqualByComparingTo(ledger.approvedVolume().subtract(ledger.effectiveVolume()));
        }
    }

    @Test
    void concurrentIdenticalReplaysProduceSingleEvent() throws Exception {
        String permitNo = newPermit("100");
        String eventNo = "D-same-" + permitNo;
        int threads = 8;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                permitService.declare(permitNo, new DeclarationRequest(eventNo, DAY, new BigDecimal("30")));
                return "OK";
            });
        }
        List<String> outcomes = runConcurrently(tasks);

        assertThat(outcomes).containsOnly("OK");
        LedgerResponse ledger = permitService.ledger(permitNo);
        assertThat(ledger.events()).hasSize(1);
        assertThat(ledger.effectiveVolume()).isEqualByComparingTo("30");
    }
}
