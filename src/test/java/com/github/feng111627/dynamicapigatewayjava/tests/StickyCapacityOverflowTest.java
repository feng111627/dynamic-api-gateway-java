package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.feng111627.dynamicapigatewayjava.balance.ConcurrencyLimiter;
import com.github.feng111627.dynamicapigatewayjava.balance.WeightedRoundRobinLoadBalancer;
import com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend;
import com.github.feng111627.dynamicapigatewayjava.gateway.Forwarder;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 业务域：粘性容量溢出——调用方已粘在某实例上，而该实例并发打满时，
 * 请求必须像未粘住的请求一样及时改走有余量的健康实例，不得忙等空转；
 * 仅当路由下所有健康实例都到上限时才返回可区分的 BACKEND_AT_CAPACITY 容量拒绝。
 */
@ExtendWith(BusinessCoverageExtension.class)
class StickyCapacityOverflowTest {

    private static final String DOMAIN = "粘性容量溢出";

    private record Rig(GatewayService gateway, List<FakeBackend> servers) implements AutoCloseable {
        @Override
        public void close() {
            servers.forEach(FakeBackend::close);
        }
    }

    /** backends: (weight, maxConcurrency) 与实例一一对应。 */
    private static Rig newRig(int[][] backendConfigs) {
        List<FakeBackend> servers = new ArrayList<>();
        List<BackendInstance> backends = new ArrayList<>();
        for (int i = 0; i < backendConfigs.length; i++) {
            FakeBackend server = new FakeBackend("backend-" + (i + 1), 0);
            servers.add(server);
            backends.add(new BackendInstance("backend-" + (i + 1), "127.0.0.1", server.port(),
                    backendConfigs[i][0], backendConfigs[i][1]));
        }
        RouteRegistry routes = new RouteRegistry(List.of(new Route("r1", "/api/", backends)));
        GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                new StickySessionStore(60_000), new HealthRegistry(), new Forwarder(),
                new WeightedRoundRobinLoadBalancer(), new ConcurrencyLimiter());
        return new Rig(gateway, servers);
    }

    private static GatewayRequest req(String caller, String query) {
        return new GatewayRequest("GET", "/api/slow", query, Map.of(), caller, null);
    }

    /**
     * 核心场景：调用方已粘在 backend-1（并发上限 1），一个长慢请求长时间占住它的唯一配额；
     * 同一调用方随后发来的请求必须在配额被占期间就快速改走空闲的 backend-2，
     * 而不是忙等空转直到占满它的请求结束（修复前这里会一直挂到超时）。
     */
    @Test
    @Business(DOMAIN)
    void 粘住实例打满时同调用方及时改走空闲健康实例且不忙等() throws Exception {
        try (Rig rig = newRig(new int[][]{{1, 1}, {1, 0}})) {
            final String caller = "sticky-caller";

            // 1) 先建立粘性：backend-1 是路由中第一个健康实例，首个请求落在它上面
            GatewayResponse bind = rig.gateway().handle(req(caller, null));
            assertEquals(GatewayResponse.ErrorKind.NONE, bind.errorKind());
            assertEquals("backend-1", bind.backendId(), "前置：先把调用方粘到 backend-1");

            // 2) 用一个长慢请求长时间占满 backend-1 的唯一并发配额（守护线程，结束时打断）
            CountDownLatch slowStarted = new CountDownLatch(1);
            Thread slow = new Thread(() -> {
                slowStarted.countDown();
                rig.gateway().handle(req(caller, "delayMs=20000"));
            });
            slow.setDaemon(true);
            slow.start();
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(150); // 确保慢请求已在 backend-1 在途、配额被占

            // 3) 同一调用方的新请求：必须在配额仍被占期间快速返回并落到空闲的 backend-2。
            //    修复前调用线程会在"选中满实例 -> 获取失败"之间无限忙循环，3 秒超时即复现挂死。
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                long t0 = System.nanoTime();
                Future<GatewayResponse> f = pool.submit(() -> rig.gateway().handle(req(caller, null)));
                GatewayResponse spill = f.get(3, TimeUnit.SECONDS);
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

                assertEquals(GatewayResponse.ErrorKind.NONE, spill.errorKind(),
                        "绑定实例已满但还有健康实例有余量时，必须改走它并成功，而不是拒绝或挂死: "
                                + spill.errorDetail());
                assertEquals("backend-2", spill.backendId(),
                        "溢出请求应临时由空闲的 backend-2 承接");
                assertTrue(elapsedMs < 2_000,
                        "溢出请求必须及时返回（实际 " + elapsedMs + "ms），不得忙等占满实例释放");
                assertTrue(slow.isAlive(), "此刻占满 backend-1 的慢请求应仍在途");
                assertEquals(1, rig.servers().get(0).maxInFlight(), "backend-1 绝不允许超发");
            } finally {
                pool.shutdownNow();
                slow.interrupt(); // 快速收尾，避免等待完整长延迟
            }
        }
    }

    /**
     * 溢出只是临时承接，不改粘性绑定：被粘的实例腾出余量后，同一调用方的后续请求
     * 应自然回到原绑定实例（打满不是故障，不踢会话、不改绑）。
     */
    @Test
    @Business(DOMAIN)
    void 溢出不改绑原实例腾出余量后回切() throws Exception {
        try (Rig rig = newRig(new int[][]{{1, 1}, {1, 0}})) {
            final String caller = "sticky-caller";
            assertEquals("backend-1", rig.gateway().handle(req(caller, null)).backendId());

            CountDownLatch started = new CountDownLatch(1);
            Thread slow = new Thread(() -> {
                started.countDown();
                rig.gateway().handle(req(caller, "delayMs=400"));
            });
            slow.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(150);

            // 满员期间溢出到 backend-2
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<GatewayResponse> f = pool.submit(() -> rig.gateway().handle(req(caller, null)));
                assertEquals("backend-2", f.get(3, TimeUnit.SECONDS).backendId());
            } finally {
                pool.shutdownNow();
            }

            slow.join(5_000);
            Thread.sleep(50); // 等 backend-1 配额释放

            // 余量恢复：粘性仍是 backend-1，请求回到它上面
            GatewayResponse back = rig.gateway().handle(req(caller, null));
            assertEquals(GatewayResponse.ErrorKind.NONE, back.errorKind());
            assertEquals("backend-1", back.backendId(), "溢出不改绑，腾出余量后应回到粘住的实例");
        }
    }

    /**
     * 边界：粘住的实例打满，且其余健康实例也全部到上限——无处可去时才返回容量拒绝，
     * 错误分类必须能与限流 429、路由未匹配 404、后端不可用 503 区分；请求不得悬着。
     */
    @Test
    @Business(DOMAIN)
    void 所有健康实例都满时返回可区分的容量拒绝() throws Exception {
        try (Rig rig = newRig(new int[][]{{1, 1}, {1, 1}})) {
            final String caller = "sticky-caller";
            assertEquals("backend-1", rig.gateway().handle(req(caller, null)).backendId());

            // 两个慢请求分别占满 backend-1（粘住的实例）和 backend-2（另一健康实例）
            ExecutorService slowPool = Executors.newFixedThreadPool(2);
            CountDownLatch slowGate = new CountDownLatch(1);
            Future<?> h1 = slowPool.submit(() -> {
                slowGate.countDown();
                rig.gateway().handle(req(caller, "delayMs=600"));
            });
            Future<?> h2 = slowPool.submit(() -> {
                slowGate.countDown();
                rig.gateway().handle(req("other-caller", "delayMs=600"));
            });
            assertTrue(slowGate.await(5, TimeUnit.SECONDS));
            Thread.sleep(200); // 两个慢请求分别占满 backend-1 / backend-2

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                // 同一调用方再来一个请求：两台都满，必须及时拿到容量拒绝而非忙等
                Future<GatewayResponse> rejectedF = pool.submit(
                        () -> rig.gateway().handle(req(caller, null)));
                GatewayResponse rejected = rejectedF.get(3, TimeUnit.SECONDS);
                assertEquals(503, rejected.status());
                assertEquals(GatewayResponse.ErrorKind.BACKEND_AT_CAPACITY, rejected.errorKind(),
                        "所有健康实例都到上限时才是容量拒绝");
                assertNotEquals(GatewayResponse.ErrorKind.RATE_LIMITED, rejected.errorKind());
                assertNotEquals(GatewayResponse.ErrorKind.ROUTE_NOT_FOUND, rejected.errorKind());
                assertNotEquals(GatewayResponse.ErrorKind.NO_HEALTHY_BACKEND, rejected.errorKind());
                assertTrue(rejected.errorDetail().contains("backend"),
                        "拒绝详情里应能看到哪些实例满了: " + rejected.errorDetail());

                h1.get(5, TimeUnit.SECONDS);
                h2.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                slowPool.shutdownNow();
            }
        }
    }
}
