package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：会话粘性——同调用方落同实例、故障自动切换、恢复后回切、请求必有明确结果。 */
@ExtendWith(BusinessCoverageExtension.class)
class StickyFailoverTest {

    private static final String DOMAIN = "会话粘性";

    private record Rig(GatewayService gateway, HealthRegistry health,
                       BackendInstance b1, BackendInstance b2,
                       FakeBackend s1, FakeBackend s2) implements AutoCloseable {
        @Override
        public void close() {
            s1.close();
            s2.close();
        }
    }

    private static Rig newRig(long sessionTtlMillis) {
        FakeBackend s1 = new FakeBackend("backend-1", 0);
        FakeBackend s2 = new FakeBackend("backend-2", 0);
        BackendInstance b1 = new BackendInstance("backend-1", "127.0.0.1", s1.port());
        BackendInstance b2 = new BackendInstance("backend-2", "127.0.0.1", s2.port());
        RouteRegistry routes = new RouteRegistry(List.of(
                new Route("r1", "/api/", List.of(b1, b2))));
        HealthRegistry health = new HealthRegistry();
        GatewayService gateway = new GatewayService(routes,
                new RateLimiter(List.of()),
                new StickySessionStore(sessionTtlMillis),
                health, new Forwarder());
        return new Rig(gateway, health, b1, b2, s1, s2);
    }

    @Test
    @Business(DOMAIN)
    void 同一会话内请求落到同一实例() {
        try (Rig rig = newRig(60_000)) {
            String first = rig.gateway().handle("/api/hello", "caller-1", "x").backendId();
            for (int i = 0; i < 10; i++) {
                GatewayResponse resp = rig.gateway().handle("/api/hello", "caller-1", "x");
                assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind());
                assertEquals(first, resp.backendId(), "会话有效期内必须粘在首次命中的实例上");
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 实例故障自动切换且请求有明确结果() {
        try (Rig rig = newRig(60_000)) {
            GatewayResponse first = rig.gateway().handle("/api/hello", "caller-1", "x");
            String stickyTo = first.backendId();

            // 打挂当前粘住的实例
            if ("backend-1".equals(stickyTo)) {
                rig.s1().close();
            } else {
                rig.s2().close();
            }

            // 故障后的请求：自动切到另一个实例，且结果是明确的（成功或分类错误），不会无声丢失
            GatewayResponse after = rig.gateway().handle("/api/hello", "caller-1", "x");
            assertEquals(GatewayResponse.ErrorKind.NONE, after.errorKind(),
                    "尚有健康实例时切换后应成功: " + after.errorDetail());
            assertNotEquals(stickyTo, after.backendId(), "必须切到另一个健康实例");

            // 后续请求稳定落在新实例上
            for (int i = 0; i < 5; i++) {
                assertEquals(after.backendId(),
                        rig.gateway().handle("/api/hello", "caller-1", "x").backendId());
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 全部实例故障时返回明确错误而非静默丢弃() throws Exception {
        FakeBackend s1 = new FakeBackend("backend-1", 0);
        BackendInstance b1 = new BackendInstance("backend-1", "127.0.0.1", s1.port());
        RouteRegistry routes = new RouteRegistry(List.of(new Route("r1", "/api/", List.of(b1))));
        GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                new StickySessionStore(60_000), new HealthRegistry(), new Forwarder());

        s1.close(); // 唯一的实例挂了
        GatewayResponse resp = gateway.handle("/api/hello", "caller-1", "x");
        assertEquals(503, resp.status());
        assertEquals(GatewayResponse.ErrorKind.NO_HEALTHY_BACKEND, resp.errorKind());
        assertTrue(resp.errorDetail().contains("backend-1"), "错误里要能看到尝试过哪些实例");
    }

    @Test
    @Business(DOMAIN)
    void 实例恢复且会话过期后可回切() {
        AtomicLong clock = new AtomicLong(0);
        FakeBackend s1 = new FakeBackend("backend-1", 0);
        FakeBackend s2 = new FakeBackend("backend-2", 0);
        try (s1; s2) {
            BackendInstance b1 = new BackendInstance("backend-1", "127.0.0.1", s1.port());
            BackendInstance b2 = new BackendInstance("backend-2", "127.0.0.1", s2.port());
            RouteRegistry routes = new RouteRegistry(List.of(
                    new Route("r1", "/api/", List.of(b1, b2))));
            HealthRegistry health = new HealthRegistry();
            GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                    new StickySessionStore(1_000, clock::get), health, new Forwarder());

            // 初始粘到 backend-1（路由里排第一的健康实例，轮询计数从 0 开始）
            assertEquals("backend-1", gateway.handle("/api/h", "c1", "x").backendId());

            // backend-1 故障：负载均衡下不一定每个请求都先试它，
            // 用不同调用方打几轮，凡试到它的请求都自动切换成功，且它被标记不健康
            s1.close();
            for (int i = 0; i < 6 && health.isHealthy("backend-1"); i++) {
                clock.addAndGet(2_000); // 让旧会话过期，排除粘性干扰
                GatewayResponse resp = gateway.handle("/api/h", "c" + (i + 2), "x");
                assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind(),
                        "尚有健康实例时请求必须成功切换: " + resp.errorDetail());
            }
            assertTrue(health.isHealthy("backend-2"));
            assertTrue(!health.isHealthy("backend-1"), "试到故障实例后应被标记不健康");

            // backend-1 恢复（重启实例 + 健康标记恢复），会话过期后几轮内应能自然回切到它
            FakeBackend s1b = new FakeBackend("backend-1", s1.port());
            try (s1b) {
                health.markHealthy("backend-1");
                boolean cameBack = false;
                for (int i = 0; i < 6 && !cameBack; i++) {
                    clock.addAndGet(2_000); // 会话过期后重新选实例
                    cameBack = "backend-1".equals(
                            gateway.handle("/api/h", "c-back", "x").backendId());
                }
                assertTrue(cameBack, "实例恢复且旧会话过期后应能回切");
            }
        }
    }
}
