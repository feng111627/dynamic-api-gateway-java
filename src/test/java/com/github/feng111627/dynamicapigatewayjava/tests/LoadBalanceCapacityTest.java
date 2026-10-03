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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：负载均衡与容量——多实例按权重分担新会话、单实例并发上限不超发不静默丢弃。 */
@ExtendWith(BusinessCoverageExtension.class)
class LoadBalanceCapacityTest {

    private static final String DOMAIN = "负载均衡与容量";

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

    private static GatewayRequest newSessionReq(String caller) {
        return new GatewayRequest("GET", "/api/res", null, Map.of(), caller, null);
    }

    @Test
    @Business(DOMAIN)
    void 等权实例间新会话均匀分散() {
        try (Rig rig = newRig(new int[][]{{1, 0}, {1, 0}})) {
            long b1 = 0;
            for (int i = 0; i < 10; i++) {
                String hit = rig.gateway().handle(newSessionReq("caller-" + i)).backendId();
                if ("backend-1".equals(hit)) {
                    b1++;
                }
            }
            assertEquals(5, b1, "等权轮询下 10 个新会话应 5/5 分散，而不是压在同一实例");
        }
    }

    @Test
    @Business(DOMAIN)
    void 新会话按可配置权重分散() {
        try (Rig rig = newRig(new int[][]{{1, 0}, {3, 0}})) {
            long b1 = 0;
            for (int i = 0; i < 8; i++) {
                String hit = rig.gateway().handle(newSessionReq("caller-" + i)).backendId();
                if ("backend-1".equals(hit)) {
                    b1++;
                }
            }
            assertEquals(2, b1, "权重 1:3 时 8 个新会话应按 2:6 分散");
        }
    }

    @Test
    @Business(DOMAIN)
    void 并发上限到达后给出可区分拒绝且不超发() throws Exception {
        try (Rig rig = newRig(new int[][]{{1, 1}})) { // 单实例，并发上限 1
            int threads = 6;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<GatewayResponse> responses = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < threads; i++) {
                String caller = "caller-" + i;
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        responses.add(rig.gateway().handle(new GatewayRequest(
                                "GET", "/api/slow", "delayMs=500", Map.of(), caller, null)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

            assertEquals(threads, responses.size(), "每个请求都必须有明确结果，不允许静默丢失");
            long ok = responses.stream()
                    .filter(r -> r.errorKind() == GatewayResponse.ErrorKind.NONE).count();
            long atCapacity = responses.stream()
                    .filter(r -> r.errorKind() == GatewayResponse.ErrorKind.BACKEND_AT_CAPACITY)
                    .count();
            assertEquals(1, ok, "上限为 1 时只能放行 1 个");
            assertEquals(threads - 1, atCapacity, "其余必须被明确拒绝");
            assertEquals(1, rig.servers().get(0).maxInFlight(), "后端在途并发绝不能超过上限");

            GatewayResponse rejected = responses.stream()
                    .filter(r -> r.errorKind() == GatewayResponse.ErrorKind.BACKEND_AT_CAPACITY)
                    .findFirst().orElseThrow();
            assertEquals(503, rejected.status());
            // 与限流、路由未匹配、后端不可用明确区分
            assertNotEquals(GatewayResponse.ErrorKind.RATE_LIMITED, rejected.errorKind());
            assertNotEquals(GatewayResponse.ErrorKind.ROUTE_NOT_FOUND, rejected.errorKind());
            assertNotEquals(GatewayResponse.ErrorKind.NO_HEALTHY_BACKEND, rejected.errorKind());
        }
    }

    @Test
    @Business(DOMAIN)
    void 多实例各自独立承担上限() throws Exception {
        try (Rig rig = newRig(new int[][]{{1, 1}, {1, 1}})) { // 双实例各限 1
            int threads = 5;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<GatewayResponse> responses = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < threads; i++) {
                String caller = "caller-" + i;
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        responses.add(rig.gateway().handle(new GatewayRequest(
                                "GET", "/api/slow", "delayMs=500", Map.of(), caller, null)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

            long ok = responses.stream()
                    .filter(r -> r.errorKind() == GatewayResponse.ErrorKind.NONE).count();
            assertEquals(2, ok, "两个实例各放行 1 个");
            assertEquals(threads - 2, responses.stream()
                    .filter(r -> r.errorKind() == GatewayResponse.ErrorKind.BACKEND_AT_CAPACITY)
                    .count(), "超出总容量的部分明确拒绝");
            assertEquals(1, rig.servers().get(0).maxInFlight());
            assertEquals(1, rig.servers().get(1).maxInFlight());
        }
    }

    @Test
    @Business(DOMAIN)
    void 请求结束后配额释放可继续承接() {
        try (Rig rig = newRig(new int[][]{{1, 1}})) {
            GatewayResponse first = rig.gateway().handle(new GatewayRequest(
                    "GET", "/api/slow", "delayMs=100", Map.of(), "caller-1", null));
            assertEquals(GatewayResponse.ErrorKind.NONE, first.errorKind());
            // 前一个请求已结束、配额已释放：后续请求正常放行
            GatewayResponse second = rig.gateway().handle(newSessionReq("caller-2"));
            assertEquals(GatewayResponse.ErrorKind.NONE, second.errorKind(),
                    "配额必须随请求结束释放: " + second.errorDetail());
        }
    }
}
