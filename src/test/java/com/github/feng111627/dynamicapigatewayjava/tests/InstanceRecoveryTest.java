package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.feng111627.dynamicapigatewayjava.balance.ConcurrencyLimiter;
import com.github.feng111627.dynamicapigatewayjava.balance.WeightedRoundRobinLoadBalancer;
import com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend;
import com.github.feng111627.dynamicapigatewayjava.gateway.Forwarder;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayRequest;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayResponse;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayService;
import com.github.feng111627.dynamicapigatewayjava.health.HealthChecker;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.StickySessionStore;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension.Business;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 业务域：实例恢复——故障实例活过来后自动回流量（无需人工改配置/重启），
 * 暖机期流量缓升，状态变化可观察，恢复不打断在途请求，健康实例不被误判。
 */
@ExtendWith(BusinessCoverageExtension.class)
class InstanceRecoveryTest {

    private static final String DOMAIN = "实例恢复";
    private static final long WARMUP = 1_000;

    private record Rig(GatewayService gateway, HealthRegistry health, HealthChecker checker,
                       AtomicLong clock, FakeBackend s1, FakeBackend s2,
                       BackendInstance b1, BackendInstance b2) implements AutoCloseable {
        @Override
        public void close() {
            s1.close();
            s2.close();
            checker.close();
        }
    }

    /** weight 可配；checker 不自动调度，测试里手动 probeOnce 保证确定性。 */
    private static Rig newRig(int weight1, int weight2) {
        AtomicLong clock = new AtomicLong(0);
        FakeBackend s1 = new FakeBackend("backend-1", 0);
        FakeBackend s2 = new FakeBackend("backend-2", 0);
        BackendInstance b1 = new BackendInstance("backend-1", "127.0.0.1", s1.port(), weight1, 0);
        BackendInstance b2 = new BackendInstance("backend-2", "127.0.0.1", s2.port(), weight2, 0);
        RouteRegistry routes = new RouteRegistry(List.of(new Route("r1", "/api/", List.of(b1, b2))));
        HealthRegistry health = new HealthRegistry(clock::get);
        // 与运行期装配一致：恢复期实例有效权重压到 1
        WeightedRoundRobinLoadBalancer balancer = new WeightedRoundRobinLoadBalancer(b ->
                health.stateOf(b.id()) == HealthRegistry.State.RECOVERING ? 1 : b.weight());
        GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                new StickySessionStore(60_000), health, new Forwarder(),
                balancer, new ConcurrencyLimiter());
        HealthChecker checker = new HealthChecker(health, routes, "/", 60_000, WARMUP, clock::get);
        return new Rig(gateway, health, checker, clock, s1, s2, b1, b2);
    }

    private static GatewayRequest newSessionReq(String caller) {
        return new GatewayRequest("GET", "/api/res", null, Map.of(), caller, null);
    }

    @Test
    @Business(DOMAIN)
    void 故障实例恢复后自动回流量且健康实例不被误判() {
        try (Rig rig = newRig(1, 1)) {
            // backend-1 挂掉：第一个新会话先试它，失败后自动切到 backend-2，并标记不健康
            rig.s1().close();
            GatewayResponse resp = rig.gateway().handle(newSessionReq("c1"));
            assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind());
            assertEquals("backend-2", resp.backendId());
            assertEquals(HealthRegistry.State.UNHEALTHY, rig.health().stateOf("backend-1"));

            // backend-1 重新拉起来：不需要人工改配置，探测一轮即进入恢复期
            FakeBackend s1b = new FakeBackend("backend-1", rig.s1().port());
            try (s1b) {
                rig.checker().probeOnce();
                assertEquals(HealthRegistry.State.RECOVERING, rig.health().stateOf("backend-1"),
                        "探测成功应自动进入恢复期");

                // 恢复期的新会话已经能分到它
                boolean landed = false;
                for (int i = 0; i < 6 && !landed; i++) {
                    landed = "backend-1".equals(
                            rig.gateway().handle(newSessionReq("rc-" + i)).backendId());
                }
                assertTrue(landed, "恢复期的实例应能承接新会话");

                // 暖机期满且持续探测成功：自动回到完全健康
                rig.clock().addAndGet(WARMUP);
                rig.checker().probeOnce();
                assertEquals(HealthRegistry.State.HEALTHY, rig.health().stateOf("backend-1"));

                // 全程健康的 backend-2 没有被误判过
                assertEquals(HealthRegistry.State.HEALTHY, rig.health().stateOf("backend-2"));
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 恢复暖机期流量缓升不会一次性砸垮实例() {
        try (Rig rig = newRig(10, 10)) {
            // 模拟 backend-1 刚被探测活过来，处于暖机期
            rig.health().markUnhealthy("backend-1");
            rig.health().markRecovering("backend-1");

            // 暖机期：有效权重 1 : 10，11 个新会话里只有 1 个落到刚恢复的实例
            long hits = 0;
            for (int i = 0; i < 11; i++) {
                if ("backend-1".equals(rig.gateway().handle(newSessionReq("w1-" + i)).backendId())) {
                    hits++;
                }
            }
            assertEquals(1, hits, "暖机期只能分到少量流量，避免恢复瞬间被打垮");

            // 暖机期满回到 HEALTHY：恢复全量权重，20 个新会话按 10:10 分担
            rig.health().markHealthy("backend-1");
            hits = 0;
            for (int i = 0; i < 20; i++) {
                if ("backend-1".equals(rig.gateway().handle(newSessionReq("w2-" + i)).backendId())) {
                    hits++;
                }
            }
            assertEquals(10, hits, "暖机结束后按配置权重 10:10 分担");
        }
    }

    @Test
    @Business(DOMAIN)
    void 健康状态变化对外可观察() {
        try (Rig rig = newRig(1, 1)) {
            List<HealthRegistry.HealthEvent> events = new CopyOnWriteArrayList<>();
            rig.health().addListener(events::add);

            // 故障 -> 恢复探测 -> 暖机期满，完整走一遍状态机
            rig.s1().close();
            rig.gateway().handle(newSessionReq("c1"));
            FakeBackend s1b = new FakeBackend("backend-1", rig.s1().port());
            try (s1b) {
                rig.checker().probeOnce();
                rig.clock().addAndGet(WARMUP);
                rig.checker().probeOnce();
            }

            List<String> transitions = events.stream()
                    .filter(e -> e.backendId().equals("backend-1"))
                    .map(e -> e.from() + "->" + e.to())
                    .toList();
            assertEquals(List.of(
                    "HEALTHY->UNHEALTHY",
                    "UNHEALTHY->RECOVERING",
                    "RECOVERING->HEALTHY"), transitions,
                    "每一次状态迁移都要能被外部观察到");
        }
    }

    @Test
    @Business(DOMAIN)
    void 恢复过程不打断正在处理的请求() throws Exception {
        try (Rig rig = newRig(1, 1)) {
            // backend-1 已故障被切走，只有 backend-2 承接
            rig.s1().close();
            rig.health().markUnhealthy("backend-1");

            // 一个慢请求正在 backend-2 上处理
            ConcurrentLinkedQueue<GatewayResponse> done = new ConcurrentLinkedQueue<>();
            CountDownLatch inFlight = new CountDownLatch(1);
            Thread slow = new Thread(() -> {
                inFlight.countDown();
                done.add(rig.gateway().handle(new GatewayRequest(
                        "GET", "/api/slow", "delayMs=400", Map.of(), "inflight-caller", null)));
            });
            slow.start();
            assertTrue(inFlight.await(5, TimeUnit.SECONDS));
            Thread.sleep(100); // 确保慢请求已在途

            // 在途期间 backend-1 恢复并走完整状态机
            FakeBackend s1b = new FakeBackend("backend-1", rig.s1().port());
            try (s1b) {
                rig.checker().probeOnce();
                rig.clock().addAndGet(WARMUP);
                rig.checker().probeOnce();
                assertEquals(HealthRegistry.State.HEALTHY, rig.health().stateOf("backend-1"));

                slow.join(5_000);
                GatewayResponse resp = done.poll();
                assertTrue(resp != null, "在途请求必须正常结束");
                assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind(),
                        "恢复过程不能打断在途请求");
                assertEquals("backend-2", resp.backendId(), "在途请求保持在原实例上完成");
            }
        }
    }
}
