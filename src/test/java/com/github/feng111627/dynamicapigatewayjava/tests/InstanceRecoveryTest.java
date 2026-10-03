package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalanceStrategy;
import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalancer;
import com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend;
import com.github.feng111627.dynamicapigatewayjava.gateway.Forwarder;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayResponse;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayService;
import com.github.feng111627.dynamicapigatewayjava.health.HealthProbe;
import com.github.feng111627.dynamicapigatewayjava.health.HealthRecoveryService;
import com.github.feng111627.dynamicapigatewayjava.health.HttpHealthProbe;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry.State;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry.StateChange;
import com.github.feng111627.dynamicapigatewayjava.sticky.StickySessionStore;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension.Business;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：实例恢复——自动探活恢复、渐进回流量、状态可观察、不误判健康实例、不打断在途请求。 */
@ExtendWith(BusinessCoverageExtension.class)
class InstanceRecoveryTest {

    private static final String DOMAIN = "实例恢复";

    private record Rig(FakeBackend s1, FakeBackend s2, BackendInstance b1, BackendInstance b2,
                       RouteRegistry routes, HealthRegistry health,
                       StickySessionStore sessions, GatewayService gateway) implements AutoCloseable {
        @Override
        public void close() {
            s1.close();
            s2.close();
        }
    }

    private static Rig newRig(long sessionTtl, AtomicLong clock, LoadBalancer balancer) {
        FakeBackend s1 = new FakeBackend("backend-1", 0);
        FakeBackend s2 = new FakeBackend("backend-2", 0);
        BackendInstance b1 = new BackendInstance("backend-1", "127.0.0.1", s1.port());
        BackendInstance b2 = new BackendInstance("backend-2", "127.0.0.1", s2.port());
        RouteRegistry routes = new RouteRegistry(List.of(new Route("r1", "/api/", List.of(b1, b2))));
        HealthRegistry health = new HealthRegistry(clock::get);
        StickySessionStore sessions = new StickySessionStore(sessionTtl, clock::get);
        GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                sessions, health, new Forwarder(Duration.ofSeconds(1), Duration.ofSeconds(2)),
                balancer, new com.github.feng111627.dynamicapigatewayjava.balance
                        .InstanceConcurrencyLimiter(), 100, clock::get);
        return new Rig(s1, s2, b1, b2, routes, health, sessions, gateway);
    }

    private static LoadBalancer balancer(AtomicLong clock, int divisor) {
        return new LoadBalancer(LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN, divisor);
    }

    private static HealthProbe realProbe() {
        return new HttpHealthProbe(Duration.ofMillis(200), Duration.ofMillis(500), "/api/probe");
    }

    /** 让一个粘在 backend-1 上的会话触发故障，从而把 backend-1 标记为不健康。 */
    private static void killBackend1ViaTraffic(Rig rig, AtomicLong clock, String caller) {
        assertEquals("backend-1", rig.gateway().handle("/api/h", caller, "x").backendId());
        rig.s1().close();
        GatewayResponse switched = rig.gateway().handle("/api/h", caller, "x");
        assertEquals("backend-2", switched.backendId(), "故障后应切到 backend-2");
        assertFalse(rig.health().isHealthy("backend-1"));
    }

    @Test
    @Business(DOMAIN)
    void 探活成功后自动恢复并重新分流量() {
        AtomicLong clock = new AtomicLong(0);
        LoadBalancer lb = balancer(clock, 4);
        try (Rig rig = newRig(1_000, clock, lb)) {
            killBackend1ViaTraffic(rig, clock, "c1");
            int deadPort = rig.b1().port();

            HealthRecoveryService recovery = new HealthRecoveryService(rig.health(), realProbe(),
                    rig.routes(), lb, 200, 0, clock::get);

            // 尚未重启：扫描不应恢复
            assertTrue(recovery.scanOnce().isEmpty(), "仍不可达时不能误恢复");
            assertFalse(rig.health().isHealthy("backend-1"));

            // 实例在原端口“复活”，再次扫描 -> 自动恢复，无需人工改配置/重启
            try (FakeBackend revived = new FakeBackend("backend-1", deadPort)) {
                List<String> recovered = recovery.scanOnce();
                assertEquals(List.of("backend-1"), recovered);
                assertTrue(rig.health().isHealthy("backend-1"));

                // 恢复后新会话能正常分到恢复实例
                boolean hit = false;
                for (int i = 0; i < 4; i++) {
                    GatewayResponse r = rig.gateway().handle("/api/h", "new-" + i, "x");
                    assertEquals(GatewayResponse.ErrorKind.NONE, r.errorKind(), r.errorDetail());
                    if ("backend-1".equals(r.backendId())) {
                        hit = true;
                    }
                }
                assertTrue(hit, "恢复后新会话必须能落到恢复实例");
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 后台周期任务也能自动恢复无需人工介入() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        LoadBalancer lb = balancer(clock, 4);
        try (Rig rig = newRig(1_000, clock, lb)) {
            killBackend1ViaTraffic(rig, clock, "c1");
            int deadPort = rig.b1().port();

            // 用真实系统时钟 + 100ms 周期任务，验证“自动”
            HealthRecoveryService recovery = new HealthRecoveryService(rig.health(), realProbe(),
                    rig.routes(), lb, 100, 0, System::currentTimeMillis);
            recovery.start();
            try (FakeBackend revived = new FakeBackend("backend-1", deadPort)) {
                long deadline = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < deadline
                        && !rig.health().isHealthy("backend-1")) {
                    Thread.sleep(50);
                }
                assertTrue(rig.health().isHealthy("backend-1"),
                        "实例复活后网关应在合理时间内自行恢复使用，不依赖人工操作");
            } finally {
                recovery.close();
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 预热期渐进回流量不把恢复实例再次打垮() {
        AtomicLong clock = new AtomicLong(0);
        LoadBalancer lb = balancer(clock, 4);
        Route route = new Route("r1", "/api/", List.of(
                new BackendInstance("backend-1", "127.0.0.1", 1),
                new BackendInstance("backend-2", "127.0.0.1", 2)));

        // backend-1 进入预热（预热 1000ms，divisor=4）
        lb.notifyRecovered("backend-1", 0, 1_000);
        int toWarming = 0;
        int total = 80;
        for (int i = 0; i < total; i++) {
            BackendInstance picked = lb.choose(route,
                    List.of(route.backends().get(0), route.backends().get(1)), clock.get())
                    .orElseThrow();
            if ("backend-1".equals(picked.id())) {
                toWarming++;
            }
        }
        assertTrue(toWarming > 0, "预热实例也应缓慢接到新会话，而不是一直 0 流量");
        assertTrue(toWarming <= total / 4 + 2,
                "预热期分给恢复实例的新会话必须被闸门限制（实际 " + toWarming + "/" + total + "）");

        // 预热结束 -> 恢复全权重，两实例均衡分担
        lb.notifyWarmupDone("backend-1");
        int toB1 = 0;
        for (int i = 0; i < total; i++) {
            if ("backend-1".equals(lb.choose(route, route.backends(), clock.get())
                    .orElseThrow().id())) {
                toB1++;
            }
        }
        assertTrue(toB1 >= 25 && toB1 <= 55,
                "预热结束后应按权重恢复均衡分担（实际 backend-1=" + toB1 + "/" + total + "）");
    }

    @Test
    @Business(DOMAIN)
    void 健康状态变化可观察且健康实例不被误判() {
        AtomicLong clock = new AtomicLong(0);
        HealthRegistry health = new HealthRegistry(clock::get);
        CopyOnWriteArrayList<StateChange> events = new CopyOnWriteArrayList<>();
        health.addListener(events::add);

        // 重复标记健康不产生事件：本来健康的实例不会被误判
        health.markHealthy("backend-1");
        health.markHealthy("backend-1");
        assertTrue(events.isEmpty(), "健康实例反复标记健康不应产生变化事件");
        assertEquals(State.HEALTHY, health.stateOf("backend-1"));

        // 真实翻转才产生事件
        health.markUnhealthy("backend-1");
        health.markUnhealthy("backend-1"); // 重复标记不重复通知
        health.markHealthy("backend-1");

        assertEquals(2, events.size(), "只应有 故障、恢复 两个翻转事件");
        assertEquals(State.UNHEALTHY, events.get(0).state());
        assertEquals(State.HEALTHY, events.get(1).state());
        assertEquals("backend-1", events.get(1).backendId());

        // 扫描只“救活”：健康实例在扫描后依旧健康
        try (Rig rig = newRig(1_000, clock, balancer(clock, 4))) {
            HealthRecoveryService recovery = new HealthRecoveryService(rig.health(),
                    backend -> true, rig.routes(), balancer(clock, 4), 100, 0, clock::get);
            recovery.scanOnce();
            assertTrue(rig.health().isHealthy("backend-1"));
            assertTrue(rig.health().isHealthy("backend-2"));
        }
    }

    @Test
    @Business(DOMAIN)
    void 恢复过程不打断正在处理的请求() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        LoadBalancer lb = balancer(clock, 4);
        try (Rig rig = newRig(100_000, clock, lb)) {
            // 先打挂 backend-1，c-stick 的请求必落在 backend-2 并粘住
            rig.s1().close();
            GatewayResponse first = rig.gateway().handle("/api/hold/x", "c-stick", "x");
            String stickyId = first.backendId();
            assertEquals("backend-2", stickyId, first.errorDetail());

            // 打开服务端挂起开关后，再发起一个会在服务端阻塞的在途请求
            rig.s2().armHolds();
            java.util.concurrent.atomic.AtomicReference<GatewayResponse> inFlightResult =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() ->
                    inFlightResult.set(rig.gateway().handle("/api/hold/x", "c-stick", "x")));
            worker.start();

            // 等 backend-2 上确实有 1 个在途请求
            long deadline = System.currentTimeMillis() + 3_000;
            while (rig.s2().activeCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, rig.s2().activeCount(), "必须先有一个在途请求");

            // 在途期间完成另一实例的“故障->恢复”，不得影响该请求
            FakeBackend revived1 = new FakeBackend("backend-1", rig.b1().port());
            HealthRecoveryService recovery = new HealthRecoveryService(rig.health(),
                    backend -> true, rig.routes(), lb, 100, 0, clock::get);
            recovery.scanOnce(); // backend-1 恢复
            clock.addAndGet(100_000);

            rig.s2().releaseAllHolds();
            worker.join(5_000);
            assertFalse(worker.isAlive(), "在途请求必须正常结束");
            GatewayResponse r = inFlightResult.get();
            assertEquals(200, r.status());
            assertEquals("backend-2", r.backendId(), "在途请求不应被恢复流程打断或改道");
            revived1.close();
        }
    }

    @Test
    @Business(DOMAIN)
    void 恢复后新粘性会话稳定落在恢复实例() {
        AtomicLong clock = new AtomicLong(0);
        LoadBalancer lb = balancer(clock, 4);
        try (Rig rig = newRig(100_000, clock, lb)) {
            killBackend1ViaTraffic(rig, clock, "old");
            try (FakeBackend revived = new FakeBackend("backend-1", rig.b1().port())) {
                rig.health().markHealthy("backend-1");

                // 找到一个首次落到 backend-1 的新会话，之后它必须稳定粘住
                String luckyCaller = null;
                for (int i = 0; i < 8 && luckyCaller == null; i++) {
                    String caller = "lucky-" + i;
                    if ("backend-1".equals(
                            rig.gateway().handle("/api/h", caller, "x").backendId())) {
                        luckyCaller = caller;
                    }
                }
                assertTrue(luckyCaller != null, "恢复实例应能接到新会话");
                for (int i = 0; i < 5; i++) {
                    assertEquals("backend-1",
                            rig.gateway().handle("/api/h", luckyCaller, "x").backendId(),
                            "已建立的粘性必须稳定");
                }
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 已粘会话按既有TTL规则到期后自然回切() {
        AtomicLong clock = new AtomicLong(0);
        LoadBalancer lb = balancer(clock, 4);
        try (Rig rig = newRig(1_000, clock, lb)) {
            killBackend1ViaTraffic(rig, clock, "c1"); // c1 粘在 backend-2
            try (FakeBackend revived = new FakeBackend("backend-1", rig.b1().port())) {
                rig.health().markHealthy("backend-1");

                // TTL 未到：c1 仍粘在 backend-2，不会被强行拉走
                clock.addAndGet(500);
                assertEquals("backend-2", rig.gateway().handle("/api/h", "c1", "x").backendId(),
                        "粘性有效期内不应被恢复流程抢占");

                // TTL 到期后：绑定失效，后续新会话周期里自然可能回到 backend-1
                clock.addAndGet(1_000);
                boolean back = false;
                for (int i = 0; i < 6; i++) {
                    clock.addAndGet(1_100); // 每次都让绑定过期，模拟“自然到期重新选择”
                    if ("backend-1".equals(
                            rig.gateway().handle("/api/h", "c1", "x").backendId())) {
                        back = true;
                        break;
                    }
                }
                assertTrue(back, "既有粘性到期后应能自然回到恢复实例");
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 探活失败保持不健康不影响其他健康实例流量() {
        AtomicLong clock = new AtomicLong(0);
        LoadBalancer lb = balancer(clock, 4);
        try (Rig rig = newRig(100_000, clock, lb)) {
            killBackend1ViaTraffic(rig, clock, "c1");
            HealthRecoveryService recovery = new HealthRecoveryService(rig.health(), realProbe(),
                    rig.routes(), lb, 100, 0, clock::get);

            for (int round = 0; round < 3; round++) {
                assertTrue(recovery.scanOnce().isEmpty(), "探活失败时不能恢复");
                assertFalse(rig.health().isHealthy("backend-1"));
            }
            // 健康的 backend-2 继续正常服务
            for (int i = 0; i < 5; i++) {
                GatewayResponse r = rig.gateway().handle("/api/h", "other-" + i, "x");
                assertEquals(200, r.status());
                assertEquals("backend-2", r.backendId());
            }
            // 状态快照外部可见
            assertTrue(rig.health().unhealthySnapshot().contains("backend-1"));
            assertEquals(State.UNHEALTHY,
                    rig.health().snapshot(List.of("backend-1", "backend-2")).get("backend-1"));
            assertEquals(State.HEALTHY,
                    rig.health().snapshot(List.of("backend-1", "backend-2")).get("backend-2"));
        }
    }
}
