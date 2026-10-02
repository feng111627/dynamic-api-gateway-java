package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.feng111627.dynamicapigatewayjava.limit.RateLimitDecision;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimitRule;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension.Business;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：请求限流——并发不超发、窗口切换不重复计数不漏计、拒绝结果可区分。 */
@ExtendWith(BusinessCoverageExtension.class)
class RateLimiterConcurrencyTest {

    private static final String DOMAIN = "请求限流";

    @Test
    @Business(DOMAIN)
    void 并发打量配额不超发() throws Exception {
        long limit = 100;
        RateLimiter limiter = new RateLimiter(List.of(
                new RateLimitRule("r1", "route-a", "*", limit, 60_000)));

        int threads = 16;
        int perThread = 50; // 总请求 800，远超配额 100
        CountDownLatch start = new CountDownLatch(1);
        LongAdder allowed = new LongAdder();
        LongAdder rejected = new LongAdder();

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int j = 0; j < perThread; j++) {
                    if (limiter.check("route-a", "caller-x").allowed()) {
                        allowed.increment();
                    } else {
                        rejected.increment();
                    }
                }
            });
        }
        for (Thread t : workers) t.start();
        start.countDown();
        for (Thread t : workers) t.join(30_000);

        assertEquals(limit, allowed.sum(), "放行数必须恰好等于配额，不能超发");
        assertEquals(threads * perThread - limit, rejected.sum());
    }

    @Test
    @Business(DOMAIN)
    void 窗口切换不重复计数不漏计() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(List.of(
                new RateLimitRule("r1", "route-a", "caller-x", 10, 1_000)), clock::get);

        // 第一个窗口打满 10 个
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.check("route-a", "caller-x").allowed(), "窗口内第 " + i + " 次应放行");
        }
        assertFalse(limiter.check("route-a", "caller-x").allowed(), "窗口内第 11 次应拒绝");

        // 切到下一个窗口：配额重置，旧窗口计数不带入（不重复计数）
        clock.set(1_000);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.check("route-a", "caller-x").allowed(), "新窗口第 " + i + " 次应放行");
        }
        assertFalse(limiter.check("route-a", "caller-x").allowed(), "新窗口第 11 次应拒绝");

        // 窗口中途的请求都计入当前窗口（不漏计）
        clock.set(2_500);
        assertTrue(limiter.check("route-a", "caller-x").allowed(), "第三个窗口首次请求应放行");
    }

    @Test
    @Business(DOMAIN)
    void 拒绝结果携带规则标识可区分() {
        RateLimiter limiter = new RateLimiter(List.of(
                new RateLimitRule("rule-caller", "route-a", "caller-x", 1, 60_000),
                new RateLimitRule("rule-route", "route-b", "@all", 1, 60_000)));

        assertTrue(limiter.check("route-a", "caller-x").allowed());
        RateLimitDecision rejected = limiter.check("route-a", "caller-x");
        assertFalse(rejected.allowed());
        assertEquals("rule-caller", rejected.ruleId(), "拒绝结果必须指出被哪条规则拦下");

        assertTrue(limiter.check("route-b", "anyone").allowed());
        RateLimitDecision rejected2 = limiter.check("route-b", "anyone-else");
        assertFalse(rejected2.allowed());
        assertEquals("rule-route", rejected2.ruleId());
    }

    @Test
    @Business(DOMAIN)
    void 不同调用方配额相互独立() {
        RateLimiter limiter = new RateLimiter(List.of(
                new RateLimitRule("r1", "route-a", "*", 2, 60_000)));

        assertTrue(limiter.check("route-a", "caller-1").allowed());
        assertTrue(limiter.check("route-a", "caller-1").allowed());
        assertFalse(limiter.check("route-a", "caller-1").allowed());
        // caller-1 超限不影响 caller-2
        assertTrue(limiter.check("route-a", "caller-2").allowed());
        assertTrue(limiter.check("route-a", "caller-2").allowed());
        assertFalse(limiter.check("route-a", "caller-2").allowed());
    }
}
