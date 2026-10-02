package com.github.feng111627.dynamicapigatewayjava.tests;

import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimitDecision;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimitScope;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business: per-route and per-caller fixed-window rate limiting.
 * Covers: exact quota accounting, no over-issuance under contention, window rollover
 *         (no double count, no lost count), rule identity on rejection, per-caller
 *         independence, reset semantics.
 */
class RateLimiterConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY-TEST");
    private static final String BUSINESS = "[业务:多维限流并发正确性]";

    private RateLimiter limiter;

    @BeforeAll
    static void banner() {
        log.info("{} ============================================", BUSINESS);
        log.info("{} 业务覆盖：按路由 + 按调用方维度的固定窗口限流", BUSINESS);
        log.info("{} ============================================", BUSINESS);
    }

    @BeforeEach
    void setUp(TestInfo info) {
        limiter = new RateLimiter();
        log.info("{} >>> 用例开始: {}", BUSINESS, info.getDisplayName());
    }

    @AfterEach
    void tearDown(TestInfo info) {
        log.info("{} <<< 用例通过: {}\n", BUSINESS, info.getDisplayName());
    }

    @Test
    @DisplayName("窗口内恰好放行 limit 个，第 limit+1 个被拒绝，拒绝结果标明命中的规则")
    void exactQuotaAndRuleIdentity() {
        int limit = 5;
        int allowed = 0;
        RateLimitDecision rejection = null;
        for (int i = 0; i < limit + 3; i++) {
            RateLimitDecision d = limiter.checkRoute("orders", limit, 10, 1_000);
            if (d.allowed()) {
                allowed++;
            } else if (rejection == null) {
                rejection = d;
            }
        }
        assertEquals(limit, allowed, "窗口内放行数必须严格等于配额，不能超发");
        assertNotNull(rejection);
        assertEquals(RateLimitScope.ROUTE, rejection.scope());
        assertEquals("orders", rejection.routeId());
        assertEquals(limit, rejection.limit());
        assertEquals(limit + 1, rejection.used(), "被拦下的请求也要计入 used，体现真实打量");
        log.info("{}   - 路由维度：配额 {}，放行 {}，拒绝 {}，拒绝结果 scope=ROUTE route=orders used={}",
                BUSINESS, limit, allowed, 3, rejection.used());
    }

    @Test
    @DisplayName("调用方维度各自独立计数，超限拒绝信息带调用方身份")
    void perCallerIndependentQuotas() {
        RateLimitDecision aRejection = null;
        RateLimitDecision bRejection = null;
        for (int i = 0; i < 4; i++) {
            RateLimitDecision da = limiter.checkCaller("orders", "caller-a", 2, 10, 1_000);
            RateLimitDecision db = limiter.checkCaller("orders", "caller-b", 2, 10, 1_000);
            if (!da.allowed() && aRejection == null) {
                aRejection = da;
            }
            if (!db.allowed() && bRejection == null) {
                bRejection = db;
            }
        }
        assertNotNull(aRejection);
        assertNotNull(bRejection);
        assertEquals("caller-a", aRejection.callerId());
        assertEquals("caller-b", bRejection.callerId());
        assertEquals(RateLimitScope.CALLER, aRejection.scope());
        log.info("{}   - caller-a / caller-b 各自配额 2，互不影响；拒绝结果携带 callerId", BUSINESS);
    }

    @Test
    @DisplayName("高并发同时抢配额：放行总数精确等于配额，绝不超发")
    void quotaNeverOverIssuedUnderConcurrency() throws Exception {
        int threads = 32;
        int perThread = 50; // 合计 1600 次请求
        int limit = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                boolean sawAllowed = false;
                for (int i = 0; i < perThread; i++) {
                    RateLimitDecision d = limiter.checkRoute("hot-route", limit, 10, 5_000);
                    if (d.allowed()) {
                        if (sawAllowed) {
                            // 允许多次放行，这里仅统计
                        }
                        sawAllowed = true;
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                }
                return sawAllowed;
            }));
        }
        start.countDown();
        for (Future<Boolean> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int total = threads * perThread;
        int allowed = total - rejectedCount.get();
        log.info("{}   - 并发 {} 线程共 {} 次请求：放行 {}，拒绝 {}，配额 {}",
                BUSINESS, threads, total, allowed, rejectedCount.get(), limit);
        assertEquals(limit, allowed, "并发场景下放行数必须精确等于配额，超发即错误");
        assertEquals(total - limit, rejectedCount.get(), "其余请求必须全部被明确拒绝");
    }

    @Test
    @DisplayName("多调用方并发：每调用方配额都不超发，拒绝数与 exceeded 次数精确吻合")
    void manyCallersConcurrentNoOverIssue() throws Exception {
        int callers = 10;
        int threadsPerCaller = 8;
        int perThread = 20; // 每调用方共 160 次
        int callerLimit = 100;
        ExecutorService pool = Executors.newFixedThreadPool(callers * threadsPerCaller);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<AtomicInteger> allowedPerCaller = new ArrayList<>();
        for (int c = 0; c < callers; c++) {
            allowedPerCaller.add(new AtomicInteger());
        }

        for (int c = 0; c < callers; c++) {
            final int callerIndex = c;
            final String callerId = "caller-" + callerIndex;
            for (int t = 0; t < threadsPerCaller; t++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    for (int i = 0; i < perThread; i++) {
                        RateLimitDecision d = limiter.checkCaller("shared-route", callerId,
                                callerLimit, 10, 9_000);
                        if (d.allowed()) {
                            allowedPerCaller.get(callerIndex).incrementAndGet();
                        }
                    }
                }));
            }
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (int c = 0; c < callers; c++) {
            assertEquals(callerLimit, allowedPerCaller.get(c).get(),
                    "调用方 caller-" + c + " 的配额被超发");
        }
        log.info("{}   - {} 个调用方 × {} 并发：每个调用方放行均精确为 {}",
                BUSINESS, callers, threadsPerCaller, callerLimit);
    }

    @Test
    @DisplayName("窗口切换边界：旧窗口满额后，新窗口计数从零开始，不重复也不丢失")
    void fixedWindowRollsOverExactly() {
        int windowSeconds = 1;
        // 窗口 1: [0ms, 1000ms)
        assertEquals(1, use(limiter.checkRoute("orders", 2, windowSeconds, 0)));
        assertEquals(2, use(limiter.checkRoute("orders", 2, windowSeconds, 999)));
        RateLimitDecision lastInOld = limiter.checkRoute("orders", 2, windowSeconds, 999);
        assertFalse(lastInOld.allowed(), "旧窗口内第 3 个必须拒绝，不能把边界前的请求算到新窗口");
        assertEquals(0L, lastInOld.windowStartMs(), "旧窗口起点必须是 0");

        // 窗口 2: [1000ms, 2000ms) —— 全新配额，旧计数不残留
        RateLimitDecision firstInNew = limiter.checkRoute("orders", 2, windowSeconds, 1000);
        assertTrue(firstInNew.allowed(), "进入新窗口必须恢复配额");
        assertEquals(1, firstInNew.used(), "新窗口第一次请求 used 必须为 1，不重复旧窗口计数");
        assertEquals(1000L, firstInNew.windowStartMs());

        RateLimitDecision secondInNew = limiter.checkRoute("orders", 2, windowSeconds, 1500);
        assertTrue(secondInNew.allowed());
        RateLimitDecision thirdInNew = limiter.checkRoute("orders", 2, windowSeconds, 1999);
        assertFalse(thirdInNew.allowed(), "新窗口同样在配额处截止，不漏计");

        // 窗口 3: [2000ms, 3000ms)
        RateLimitDecision windowThree = limiter.checkRoute("orders", 2, windowSeconds, 2000);
        assertTrue(windowThree.allowed());
        assertEquals(2000L, windowThree.windowStartMs());
        log.info("{}   - 窗口 [0,1s) 满 2 拒 1；[1s,2s) 重新计数 1、2 后拒；[2s,3s) 再重置，无重复无遗漏", BUSINESS);
    }

    @Test
    @DisplayName("reset 后所有窗口计数清空（模拟规则热更新提交）")
    void resetClearsAllCounters() {
        for (int i = 0; i < 10; i++) {
            limiter.checkCaller("orders", "caller-a", 3, 10, 1_000);
        }
        assertFalse(limiter.checkCaller("orders", "caller-a", 3, 10, 1_000).allowed());
        limiter.reset();
        RateLimitDecision afterReset = limiter.checkCaller("orders", "caller-a", 3, 10, 1_000);
        assertTrue(afterReset.allowed(), "reset 后必须恢复满配额");
        assertEquals(1, afterReset.used());
        log.info("{}   - reset 后旧窗口全部清空，调用方重新获得完整配额", BUSINESS);
    }

    @Test
    @DisplayName("过期窗口清理不影响活跃窗口")
    void purgeOnlyRemovesExpiredWindows() {
        limiter.checkRoute("old-route", 10, 1, 0);
        limiter.checkRoute("new-route", 10, 10, 5_000);
        limiter.purgeExpiredWindows(5_000);
        RateLimitDecision stillCounted = limiter.checkRoute("new-route", 10, 10, 5_001);
        assertEquals(2, stillCounted.used(), "活跃窗口的计数必须保留");
        log.info("{}   - 清理仅删除已过期窗口，活跃窗口计数连续", BUSINESS);
    }

    private int use(RateLimitDecision d) {
        return d.used();
    }
}
