package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.feng111627.dynamicapigatewayjava.balance.InstanceConcurrencyLimiter;
import com.github.feng111627.dynamicapigatewayjava.balance.InstanceConcurrencyLimiter.AcquireOutcome;
import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalanceStrategy;
import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalancer;
import com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend;
import com.github.feng111627.dynamicapigatewayjava.gateway.Forwarder;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayResponse;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayService;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.StickySessionStore;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension.Business;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：负载均衡与容量保护——权重分散、多实例分担、单实例并发上限不超发、排队与可区分拒绝。 */
@ExtendWith(BusinessCoverageExtension.class)
class LoadBalanceConcurrencyTest {

    private static final String DOMAIN = "负载均衡与容量保护";

    private static Route route(BackendInstance... backends) {
        return new Route("r1", "/api/", List.of(backends));
    }

    private static LoadBalancer wrr() {
        return new LoadBalancer(LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN, 100_000);
    }

    @Test
    @Business(DOMAIN)
    void 新会话按权重分散不会总压同一实例() {
        LoadBalancer lb = wrr();
        BackendInstance b1 = new BackendInstance("b1", "h", 1, 1, 0);
        BackendInstance b2 = new BackendInstance("b2", "h", 2, 1, 0);
        BackendInstance b3 = new BackendInstance("b3", "h", 3, 2, 0);
        Route r = route(b1, b2, b3);

        Map<String, Integer> counts = new HashMap<>();
        int rounds = 80;
        for (int i = 0; i < rounds; i++) {
            String id = lb.choose(r, List.of(b1, b2, b3), 0).orElseThrow().id();
            counts.merge(id, 1, Integer::sum);
        }
        // 权重 1:1:2 => 期望约 20/20/40，都必须明显分到流量
        assertTrue(counts.getOrDefault("b1", 0) >= 10, "b1 应分担流量: " + counts);
        assertTrue(counts.getOrDefault("b2", 0) >= 10, "b2 应分担流量: " + counts);
        assertTrue(counts.getOrDefault("b3", 0) >= 25, "b3 权重最大应分担更多: " + counts);
        assertTrue(counts.getOrDefault("b3", 0) <= 55, "权重分布不能失衡: " + counts);
        assertEquals(rounds, counts.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @Business(DOMAIN)
    void 等权实例近似均摊且短期平滑() {
        LoadBalancer lb = wrr();
        BackendInstance b1 = new BackendInstance("b1", "h", 1, 1, 0);
        BackendInstance b2 = new BackendInstance("b2", "h", 2, 1, 0);
        Route r = route(b1, b2);

        // 平滑 WRR：等权两实例必须严格交替，不能连续压同一个
        String prev = null;
        for (int i = 0; i < 20; i++) {
            String id = lb.choose(r, List.of(b1, b2), 0).orElseThrow().id();
            if (prev != null) {
                assertFalse(id.equals(prev), "等权时必须平滑交替，不能连续命中同一实例");
            }
            prev = id;
        }
    }

    @Test
    @Business(DOMAIN)
    void 并发上限不超发() throws Exception {
        InstanceConcurrencyLimiter limiter = new InstanceConcurrencyLimiter();
        int cap = 3;
        int threads = 20;
        CountDownLatch holding = new CountDownLatch(cap);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // 先占满 cap 个名额
            for (int i = 0; i < cap; i++) {
                pool.submit(() -> {
                    var res = limiter.acquire("b1", cap, 100);
                    if (res.acquired()) {
                        holding.countDown();
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        limiter.release("b1", cap);
                    }
                });
            }
            assertTrue(holding.await(2, TimeUnit.SECONDS), "前 cap 个请求必须全部拿到名额");

            AtomicInteger activeSeen = new AtomicInteger();
            AtomicInteger over = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(threads - cap);
            // 其余线程立刻涌入，观察是否超出上限
            for (int i = cap; i < threads; i++) {
                pool.submit(() -> {
                    var res = limiter.acquire("b1", cap, 0); // 不排队
                    if (res.acquired()) {
                        int now = limiter.activeCount("b1");
                        activeSeen.accumulateAndGet(now, Math::max);
                        if (now > cap) {
                            over.incrementAndGet();
                        }
                        limiter.release("b1", cap);
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(0, over.get(), "并发绝不能超发");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Business(DOMAIN)
    void 到达上限后排队等待稍后可得名额() throws Exception {
        InstanceConcurrencyLimiter limiter = new InstanceConcurrencyLimiter();
        int cap = 1;
        // 占住唯一名额
        var first = limiter.acquire("b1", cap, 0);
        assertTrue(first.acquired());

        // 排队者：给足等待时间，持有者稍后释放，排队者应成功
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> f = pool.submit(() -> {
            var res = limiter.acquire("b1", cap, 1_000);
            assertEquals(AcquireOutcome.ACQUIRED, res.outcome(), "应在超时前等到空位");
            assertTrue(res.acquired());
            limiter.release("b1", cap);
        });
        Thread.sleep(150);
        limiter.release("b1", cap);
        f.get(2, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, limiter.activeCount("b1"));
    }

    @Test
    @Business(DOMAIN)
    void 排队超时给出明确结果而不是无限阻塞或静默丢弃() throws Exception {
        InstanceConcurrencyLimiter limiter = new InstanceConcurrencyLimiter();
        var first = limiter.acquire("b1", 1, 0);
        assertTrue(first.acquired());
        long t0 = System.currentTimeMillis();
        var res = limiter.acquire("b1", 1, 200);
        long elapsed = System.currentTimeMillis() - t0;
        assertFalse(res.acquired(), "拿不到名额时必须明确拒绝");
        assertEquals(AcquireOutcome.TIMEOUT, res.outcome(), "拒绝结果要能区分是“容量超时”");
        assertTrue(elapsed >= 180 && elapsed < 800, "应等待约一个超时窗口而不是立即返回");
        limiter.release("b1", 1);
    }

    private record Rig(FakeBackend s1, FakeBackend s2, GatewayService gateway) implements AutoCloseable {
        @Override
        public void close() {
            s1.close();
            s2.close();
        }
    }

    private static Rig rig(int c1, int c2, long acquireTimeoutMillis) {
        FakeBackend s1 = new FakeBackend("b1", 0);
        FakeBackend s2 = new FakeBackend("b2", 0);
        BackendInstance b1 = new BackendInstance("b1", "127.0.0.1", s1.port(), 1, c1);
        BackendInstance b2 = new BackendInstance("b2", "127.0.0.1", s2.port(), 1, c2);
        RouteRegistry routes = new RouteRegistry(List.of(new Route("r1", "/api/", List.of(b1, b2))));
        GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                new StickySessionStore(60_000), new HealthRegistry(),
                new Forwarder(Duration.ofSeconds(1), Duration.ofSeconds(30)),
                wrr(), new InstanceConcurrencyLimiter(), acquireTimeoutMillis,
                System::currentTimeMillis);
        return new Rig(s1, s2, gateway);
    }

    @Test
    @Business(DOMAIN)
    void 网关层面并发打满返回可区分的OVERLOADED() throws Exception {
        // 两实例各 1 个并发，不排队；挂住前 2 个请求后，第 3 个新会话必须得到 BACKEND_OVERLOADED
        try (Rig rig = rig(1, 1, 0)) {
            rig.s1().armHolds();
            rig.s2().armHolds();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch entered = new CountDownLatch(2);
            // 用两个不同调用方，确保占住两个实例
            pool.submit(() -> {
                GatewayResponse r = rig.gateway().handle("/api/hold/x", "caller-1", "x");
                entered.countDown();
                return r;
            });
            pool.submit(() -> {
                GatewayResponse r = rig.gateway().handle("/api/hold/x", "caller-2", "x");
                entered.countDown();
                return r;
            });
            long deadline = System.currentTimeMillis() + 3_000;
            while ((rig.s1().activeCount() + rig.s2().activeCount()) < 2
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(2, rig.s1().activeCount() + rig.s2().activeCount(), "两实例各占 1 个在途");

            GatewayResponse rejected = rig.gateway().handle("/api/hold/x", "caller-3", "x");
            assertEquals(503, rejected.status());
            assertEquals(GatewayResponse.ErrorKind.BACKEND_OVERLOADED, rejected.errorKind(),
                    "容量打满的拒绝必须可与限流/无路由/后端全挂区分");
            assertTrue(rejected.errorDetail().contains("capacity"),
                    "拒绝明细应说明是容量原因: " + rejected.errorDetail());

            rig.s1().releaseAllHolds();
            rig.s2().releaseAllHolds();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Business(DOMAIN)
    void OVERLOADED与限流无路由后端全挂可区分() throws Exception {
        // 1) 路由不匹配
        try (Rig rig = rig(1, 1, 0)) {
            GatewayResponse noRoute = rig.gateway().handle("/nope/x", "c", "x");
            assertEquals(404, noRoute.status());
            assertEquals(GatewayResponse.ErrorKind.ROUTE_NOT_FOUND, noRoute.errorKind());

            // 2) 限流：直接给一个 1 配额的限流器
            FakeBackend s = new FakeBackend("b", 0);
            try (s) {
                BackendInstance b = new BackendInstance("b", "127.0.0.1", s.port(), 1, 0);
                RouteRegistry routes = new RouteRegistry(List.of(new Route("r", "/api/", List.of(b))));
                GatewayService gw = new GatewayService(routes,
                        new RateLimiter(List.of(new com.github.feng111627.dynamicapigatewayjava.limit
                                .RateLimitRule("rl", "*", "*", 1, 60_000))),
                        new StickySessionStore(60_000), new HealthRegistry(),
                        new Forwarder(), wrr(), new InstanceConcurrencyLimiter(),
                        0, System::currentTimeMillis);
                assertEquals(GatewayResponse.ErrorKind.NONE,
                        gw.handle("/api/h", "c", "x").errorKind());
                GatewayResponse limited = gw.handle("/api/h", "c", "x");
                assertEquals(429, limited.status());
                assertEquals(GatewayResponse.ErrorKind.RATE_LIMITED, limited.errorKind());
            }

            // 3) 后端全挂
            rig.s1().close();
            rig.s2().close();
            GatewayResponse noBackend = rig.gateway().handle("/api/h", "fresh", "x");
            assertEquals(503, noBackend.status());
            assertEquals(GatewayResponse.ErrorKind.NO_HEALTHY_BACKEND, noBackend.errorKind(),
                    "后端全挂必须与容量打满区分开");
        }
    }

    @Test
    @Business(DOMAIN)
    void 某实例打满时新会话落到其他健康实例() throws Exception {
        // b1 容量 1 且挂住；b2 容量充足。b1 满了以后，新会话不应被拒绝而应转给 b2
        try (Rig rig = rig(1, 0, 0)) {
            rig.s1().armHolds();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            CountDownLatch b1Busy = new CountDownLatch(1);
            pool.submit(() -> {
                GatewayResponse r = rig.gateway().handle("/api/hold/x", "caller-1", "x");
                b1Busy.countDown();
                return r;
            });
            long deadline = System.currentTimeMillis() + 3_000;
            while (rig.s1().activeCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, rig.s1().activeCount());

            for (int i = 0; i < 4; i++) {
                GatewayResponse r = rig.gateway().handle("/api/echo/x", "other-" + i, "x");
                assertEquals(GatewayResponse.ErrorKind.NONE, r.errorKind(),
                        "b1 满后其余健康实例要继续分担，不应直接拒绝: " + r.errorDetail());
                assertEquals("b2", r.backendId(), "打满实例之外的健康实例应承接流量");
            }

            rig.s1().releaseAllHolds();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
