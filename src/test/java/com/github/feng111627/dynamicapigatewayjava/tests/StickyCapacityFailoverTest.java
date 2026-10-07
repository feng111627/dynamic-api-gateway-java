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
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：粘性容量切换——调用方粘住的实例打满时，及时改走有余量的健康实例，不卡住不空转。 */
@ExtendWith(BusinessCoverageExtension.class)
class StickyCapacityFailoverTest {

    private static final String DOMAIN = "粘性容量切换";

    private record Rig(GatewayService gateway, ConcurrencyLimiter limiter,
                       List<FakeBackend> servers) implements AutoCloseable {
        @Override
        public void close() {
            servers.forEach(FakeBackend::close);
        }
    }

    /** 两个实例，各自并发上限 1，权重相同。 */
    private static Rig newRig() {
        int[][] cfg = {{1, 1}, {1, 1}};
        List<FakeBackend> servers = new ArrayList<>();
        List<BackendInstance> backends = new ArrayList<>();
        for (int i = 0; i < cfg.length; i++) {
            FakeBackend server = new FakeBackend("backend-" + (i + 1), 0);
            servers.add(server);
            backends.add(new BackendInstance("backend-" + (i + 1), "127.0.0.1", server.port(),
                    cfg[i][0], cfg[i][1]));
        }
        RouteRegistry routes = new RouteRegistry(List.of(new Route("r1", "/api/", backends)));
        ConcurrencyLimiter limiter = new ConcurrencyLimiter();
        GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                new StickySessionStore(60_000), new HealthRegistry(), new Forwarder(),
                new WeightedRoundRobinLoadBalancer(), limiter);
        return new Rig(gateway, limiter, servers);
    }

    private static GatewayRequest req(String caller, String query) {
        return new GatewayRequest("GET", "/api/slow", query, Map.of(), caller, null);
    }

    /**
     * 复现：调用方 alice 已粘在 backend-1；backend-1 被一个慢请求占满（上限 1），
     * 此时 alice 的下一个请求必须及时改走空闲的 backend-2，而不是卡在原地空转。
     */
    @Test
    @Business(DOMAIN)
    void 粘住的实例打满时及时改走空闲健康实例() throws Exception {
        try (Rig rig = newRig()) {
            // 1. 先发一个普通请求让 alice 产生粘性绑定
            GatewayResponse bind = rig.gateway().handle(req("alice", null));
            assertEquals(GatewayResponse.ErrorKind.NONE, bind.errorKind());
            String boundId = bind.backendId();
            String otherId = "backend-1".equals(boundId) ? "backend-2" : "backend-1";

            // 2. 用另一个调用方在 alice 绑定的实例上挂一个慢请求，把它的并发打满
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch slowEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> slow = pool.submit(() -> {
                assertTrue(rig.limiter().tryAcquire(boundId, 1), "测试前置：绑定实例应能占满");
                slowEntered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    rig.limiter().release(boundId);
                }
            });
            assertTrue(slowEntered.await(5, TimeUnit.SECONDS));
            try {
                // 3. alice 的下一个请求：绑定实例已满，必须在超时内成功落到另一个实例
                Future<GatewayResponse> f = pool.submit(() -> rig.gateway().handle(req("alice", null)));
                GatewayResponse resp;
                try {
                    resp = f.get(3, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new AssertionError(
                            "粘性请求卡在已满实例上空转，未及时改走空闲实例", e);
                }
                assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind(),
                        "还有空闲健康实例时请求必须成功: " + resp.errorDetail());
                assertEquals(otherId, resp.backendId(), "必须改走有余量的另一个健康实例");

                // 4. 绑定实例的在途配额没有被超发
                assertEquals(1, rig.limiter().inFlight(boundId), "满员实例不允许超发");
            } finally {
                release.countDown();
                slow.get(5, TimeUnit.SECONDS);
                pool.shutdownNow();
            }

            // 5. 改走成功后按既有"切换即重绑"语义粘在新实例上：绑定实例恢复余量后也不再回跳，
            //    直到会话 TTL 过期——与故障切换的会话迁移语义保持一致
            GatewayResponse again = rig.gateway().handle(req("alice", null));
            assertEquals(GatewayResponse.ErrorKind.NONE, again.errorKind());
            assertEquals(otherId, again.backendId(), "改走成功后会话应粘在新实例上");
        }
    }

    /**
     * 边界：两个健康实例都被打满（各上限 1），已粘住的调用方也无处可去时，
     * 必须快速返回明确的 503 BACKEND_AT_CAPACITY，不卡住、不空转、不超发，
     * 且与 429 限流、404 路由未匹配、503 NO_HEALTHY_BACKEND 明确区分。
     */
    @Test
    @Business(DOMAIN)
    void 所有健康实例都打满时粘住的调用方得到明确容量拒绝() throws Exception {
        Rig rig = newRig();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // alice 先建立粘性（轮询计数从 0 开始，首个新会话落 backend-1）
            assertEquals("backend-1",
                    rig.gateway().handle(req("alice", null)).backendId());

            // 直接用网关的并发计数器把两个健康实例各占满（上限 1），不依赖请求调度顺序
            assertTrue(rig.limiter().tryAcquire("backend-1", 1));
            assertTrue(rig.limiter().tryAcquire("backend-2", 1));
            Future<GatewayResponse> f;
            try {
                f = pool.submit(() -> rig.gateway().handle(req("alice", null)));
                GatewayResponse resp;
                try {
                    resp = f.get(3, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new AssertionError("全部实例满员时请求卡住未返回容量拒绝", e);
                }
                assertEquals(503, resp.status());
                assertEquals(GatewayResponse.ErrorKind.BACKEND_AT_CAPACITY, resp.errorKind());
                assertNotEquals(GatewayResponse.ErrorKind.RATE_LIMITED, resp.errorKind());
                assertNotEquals(GatewayResponse.ErrorKind.ROUTE_NOT_FOUND, resp.errorKind());
                assertNotEquals(GatewayResponse.ErrorKind.NO_HEALTHY_BACKEND, resp.errorKind());
                assertEquals(1, rig.limiter().inFlight("backend-1"),
                        "容量拒绝路径不允许在满员实例上超发");
                assertEquals(1, rig.limiter().inFlight("backend-2"));
            } finally {
                rig.limiter().release("backend-1");
                rig.limiter().release("backend-2");
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            // 容量拒绝不能破坏粘性绑定：配额释放后，alice 仍粘在 backend-1
            GatewayResponse after = rig.gateway().handle(req("alice", null));
            assertEquals(GatewayResponse.ErrorKind.NONE, after.errorKind(),
                    "配额释放后请求必须恢复成功: " + after.errorDetail());
            assertEquals("backend-1", after.backendId(), "容量拒绝不应解除粘性绑定");
        } finally {
            pool.shutdownNow();
            rig.close();
        }
    }

    /** 未被粘住的普通请求不受影响：没有绑定时按权重分散，不被容量逻辑误伤。 */
    @Test
    @Business(DOMAIN)
    void 无绑定请求仍按权重分散到健康实例() {
        try (Rig rig = newRig()) {
            long b1 = 0;
            for (int i = 0; i < 10; i++) {
                String hit = rig.gateway().handle(req("caller-" + i, null)).backendId();
                if ("backend-1".equals(hit)) {
                    b1++;
                }
            }
            assertEquals(5, b1, "等权两个实例下 10 个新会话应 5/5 分散");
        }
    }
}
