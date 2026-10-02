package com.github.feng111627.dynamicapigatewayjava.tests;

import com.github.feng111627.dynamicapigatewayjava.config.GatewayProperties;
import com.github.feng111627.dynamicapigatewayjava.core.DispatchOutcome;
import com.github.feng111627.dynamicapigatewayjava.core.GatewayDispatcher;
import com.github.feng111627.dynamicapigatewayjava.forward.BackendForwarder;
import com.github.feng111627.dynamicapigatewayjava.forward.ForwardRequest;
import com.github.feng111627.dynamicapigatewayjava.forward.ForwardResponse;
import com.github.feng111627.dynamicapigatewayjava.health.HealthManager;
import com.github.feng111627.dynamicapigatewayjava.health.HealthStatus;
import com.github.feng111627.dynamicapigatewayjava.loadbalance.RoundRobinSelector;
import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.model.GatewayConfig;
import com.github.feng111627.dynamicapigatewayjava.model.RouteRule;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.routing.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.session.StickySessionStore;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business: session stickiness + instance failover/reattach.
 * Uses a programmable fake forwarder and the real HealthManager/StickySessionStore,
 * so failure, recovery and timing are fully deterministic.
 */
class StickyFailoverTest {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY-TEST");
    private static final String BUSINESS = "[业务:会话粘性与故障切换]";

    private static final BackendInstance B1 = new BackendInstance("b1", "http://127.0.0.1:18081");
    private static final BackendInstance B2 = new BackendInstance("b2", "http://127.0.0.1:18082");

    private RouteRegistry registry;
    private HealthManager health;
    private FakeForwarder forwarder;
    private StickySessionStore store;
    private GatewayDispatcher dispatcher;
    private GatewayProperties properties;

    @BeforeAll
    static void banner() {
        log.info("{} ============================================", BUSINESS);
        log.info("{} 业务覆盖：粘性绑定、故障切换、恢复回切、请求不丢不重发", BUSINESS);
        log.info("{} ============================================", BUSINESS);
    }

    @BeforeEach
    void setUp(TestInfo info) {
        registry = new RouteRegistry();
        health = new HealthManager(1, 1, 0);
        forwarder = new FakeForwarder();
        store = new StickySessionStore();
        properties = new GatewayProperties();
        properties.setStickyTtlMs(10_000);
        properties.setStickyReattachBackoffMs(500);
        dispatcher = new GatewayDispatcher(registry, new RateLimiter(), health, store,
                new RoundRobinSelector(), forwarder, properties);
        RouteRule sticky = new RouteRule("orders", "/orders", List.of(B1, B2), true, null);
        registry.atomicUpdate(new GatewayConfig(0, List.of(sticky)));
        health.syncInstances(List.of(B1, B2));
        log.info("{} >>> 用例开始: {}", BUSINESS, info.getDisplayName());
    }

    @AfterEach
    void tearDown(TestInfo info) {
        log.info("{} <<< 用例通过: {}\n", BUSINESS, info.getDisplayName());
    }

    private DispatchOutcome get(String caller, long now) {
        return dispatcher.dispatch(new ForwardRequest("GET", "/orders/x", null, Map.of(), new byte[0]),
                caller, now);
    }

    private DispatchOutcome call(String method, String caller, long now) {
        return dispatcher.dispatch(new ForwardRequest(method, "/orders/x", null, Map.of(), new byte[0]),
                caller, now);
    }

    @Test
    @DisplayName("正常时同一调用方始终粘性在同一实例，不同调用方独立绑定")
    void stickyHoldsWhileHealthy() {
        DispatchOutcome o1 = get("caller-a", 1_000);
        DispatchOutcome o2 = get("caller-a", 2_000);
        DispatchOutcome o3 = get("caller-a", 3_000);
        assertEquals("b1", o1.instanceId());
        assertEquals("b1", o2.instanceId(), "同调用方第二次请求必须落在同一实例");
        assertEquals("b1", o3.instanceId());
        assertEquals(200, o3.httpStatus());
        assertEquals(3, forwarder.sentTo("b1"), "三次都应只打到 b1");

        DispatchOutcome b = get("caller-b", 1_000);
        DispatchOutcome b2 = get("caller-b", 2_000);
        assertEquals("b2", b.instanceId(), "另一调用方独立轮询到 b2");
        assertEquals("b2", b2.instanceId());
        log.info("{}   - caller-a 连续 3 次→b1；caller-b 连续 2 次→b2，绑定互不干扰", BUSINESS);
    }

    @Test
    @DisplayName("粘性实例故障：请求自动切到其他实例，当次即成功，且每个实例只被发一次")
    void failoverHappensWithinSameRequest() {
        DispatchOutcome bound = get("caller-a", 1_000);
        assertEquals("b1", bound.instanceId());

        forwarder.failTransport("b1"); // b1 连接失败（模拟进程挂掉/端口拒绝）
        DispatchOutcome failed = get("caller-a", 2_000);

        assertEquals(200, failed.httpStatus(), "故障切换后当次请求必须拿到成功结果，不能丢弃");
        assertEquals("b2", failed.instanceId(), "必须切换到健康实例 b2");
        assertEquals(List.of("b1", "b2"), failed.attemptedInstances(),
                "b1 只发一次，失败后切 b2，不得对 b1 重发");
        assertEquals(HealthStatus.UNHEALTHY, health.statusOf("b1"));
        assertEquals(HealthStatus.HEALTHY, health.statusOf("b2"));
        log.info("{}   - b1 连接失败→同请求内切换 b2 成功；尝试序列 [b1,b2]，无重复发送", BUSINESS);

        // 后续请求继续稳定落在 b2
        DispatchOutcome again = get("caller-a", 3_000);
        assertEquals("b2", again.instanceId());
        assertEquals(List.of("b2"), again.attemptedInstances(), "已切换后不再触碰故障实例");
        log.info("{}   - 后续请求稳定落在 b2，不再访问 b1", BUSINESS);
    }

    @Test
    @DisplayName("上游 5xx（GET 幂等）：同样触发一次切换，且故障实例不重发")
    void failoverOnUpstream5xxForIdempotentGet() {
        get("caller-a", 1_000);
        forwarder.fail5xx("b1");
        DispatchOutcome out = get("caller-a", 2_000);
        assertEquals(200, out.httpStatus());
        assertEquals("b2", out.instanceId());
        assertEquals(List.of("b1", "b2"), out.attemptedInstances());
        assertEquals(2, forwarder.sentTo("b1"), "b1 共发送两次：一次绑定请求 + 故障请求内一次，绝不重发");
        assertEquals(1, forwarder.sentTo("b2"));
        log.info("{}   - b1 返回 503，GET 请求切换 b2 成功，单次请求内 b1 未被二次发送", BUSINESS);
    }

    @Test
    @DisplayName("非幂等 POST 收到 5xx 不做故障切换，避免一次请求重复写入，仍返回明确结果")
    void noFailoverForNonIdempotentWrite() {
        forwarder.fail5xx("b1");
        DispatchOutcome out = call("POST", "caller-a", 1_000);
        assertEquals(503, out.httpStatus(), "POST 不重试，直接把上游 503 作为确定结果");
        assertEquals("b1", out.instanceId());
        assertEquals(List.of("b1"), out.attemptedInstances(), "POST 绝不能被发到第二个实例");
        assertEquals(1, forwarder.sentTo("b1"));
        assertEquals(0, forwarder.sentTo("b2"));
        assertEquals("UPSTREAM_ERROR", out.code());
        log.info("{}   - POST 遇 503 不切换、不重发，结果=UPSTREAM_ERROR/503", BUSINESS);
    }

    @Test
    @DisplayName("所有实例都故障：返回明确的 ALL_INSTANCES_FAILED/502，并列出尝试过的实例")
    void allInstancesDownReturnsExplicitError() {
        DispatchOutcome bound = get("caller-a", 1_000);
        assertEquals("b1", bound.instanceId());
        forwarder.failTransport("b1");
        forwarder.failTransport("b2");

        DispatchOutcome out = get("caller-a", 2_000);
        assertEquals(502, out.httpStatus());
        assertEquals("ALL_INSTANCES_FAILED", out.code());
        assertEquals(2, forwarder.sentTo("b1"), "b1：绑定请求 1 次 + 故障请求内 1 次，单请求内无重发");
        assertEquals(1, forwarder.sentTo("b2"), "b2：仅在故障请求内尝试 1 次");
        assertEquals(List.of("b1", "b2"), out.attemptedInstances());
        assertNotNull(out.message());
        log.info("{}   - 两个实例均故障：502/ALL_INSTANCES_FAILED，请求有明确结果而非静默丢失；尝试={}",
                BUSINESS, out.attemptedInstances());

        DispatchOutcome retry = dispatcher.dispatch(
                new ForwardRequest("GET", "/other", null, Map.of(), new byte[0]), "caller-x", 2_000);
        // /other 没有路由 → 404，属于另一种明确结果
        assertEquals(404, retry.httpStatus());
    }

    @Test
    @DisplayName("无健康实例时返回 NO_HEALTHY_INSTANCE，健康集为空也不丢请求")
    void noHealthyInstanceAtDispatch() {
        health.onForwardFailure(B1);
        health.onForwardFailure(B2);
        DispatchOutcome out = get("caller-new", 1_000);
        assertEquals(502, out.httpStatus());
        assertEquals("NO_HEALTHY_INSTANCE", out.code());
        assertEquals(List.of(), out.attemptedInstances(), "不会向已熔断实例发流量");
        log.info("{}   - 全部实例熔断：502/NO_HEALTHY_INSTANCE，不发无效请求", BUSINESS);
    }

    @Test
    @DisplayName("恢复回切：原实例探活恢复并经过回退冷却后，粘性会话切回原实例")
    void stickyReattachesAfterRecoveryAndBackoff() {
        DispatchOutcome bound = get("caller-a", 1_000); // 绑定 b1
        forwarder.failTransport("b1");
        DispatchOutcome moved = get("caller-a", 2_000); // 切到 b2
        assertEquals("b2", moved.instanceId());

        // b1 恢复：主动探活在 t=3000 连续成功，状态转 HEALTHY
        forwarder.recover("b1");
        health.recordProbe("b1", true, 3_000);
        assertEquals(HealthStatus.HEALTHY, health.statusOf("b1"));

        // 冷却期未满：仍留在 b2，避免刚恢复就抖动回切
        DispatchOutcome duringBackoff = get("caller-a", 3_200);
        assertEquals("b2", duringBackoff.instanceId(), "回退冷却内不应回切");
        assertFalse(duringBackoff.stickyReattached());

        // 冷却期满（3000 + 500 = 3500）：切回原始实例 b1
        DispatchOutcome reattached = get("caller-a", 3_600);
        assertEquals("b1", reattached.instanceId(), "冷却后应回切原实例 b1");
        assertTrue(reattached.stickyReattached(), "结果需标明发生了回切");
        assertEquals(List.of("b1"), reattached.attemptedInstances(), "回切成功时只向 b1 发送一次");

        DispatchOutcome stable = get("caller-a", 4_000);
        assertEquals("b1", stable.instanceId(), "回切后保持稳定");
        log.info("{}   - b1 故障→b2；恢复后冷却内留 b2；冷却满回切 b1 并稳定", BUSINESS);
    }

    @Test
    @DisplayName("粘性 TTL 过期后重新选择实例（绑定失效条件之一）")
    void stickyBindingExpiresAfterTtl() {
        DispatchOutcome bound = get("caller-a", 1_000);
        assertEquals("b1", bound.instanceId());
        // TTL=10s：在 t=11001 时绑定已过期，按轮询重新分配（此时 b2）
        DispatchOutcome rebound = get("caller-a", 11_001);
        assertEquals("b2", rebound.instanceId());
        log.info("{}   - TTL=10s 到期后粘性失效，重新轮询分配到 b2", BUSINESS);
    }

    @Test
    @DisplayName("并发故障切换：同一调用方的并发请求不会出现重复发送，全部有明确结果")
    void concurrentFailoverNoDoubleSend() throws Exception {
        get("caller-a", 1_000); // 先绑定 b1
        forwarder.failTransport("b1");
        int threads = 16;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<DispatchOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return get("caller-a", 2_000);
            }));
        }
        start.countDown();
        int ok = 0;
        int errors = 0;
        for (java.util.concurrent.Future<DispatchOutcome> f : futures) {
            DispatchOutcome o = f.get();
            if (o.httpStatus() == 200 && "b2".equals(o.instanceId())) {
                ok++;
            } else {
                errors++;
            }
            // 每个返回结果内部，任何实例最多出现一次
            long distinct = o.attemptedInstances().stream().distinct().count();
            assertEquals(distinct, o.attemptedInstances().size(), "单次请求不得对同一实例发送两次");
        }
        pool.shutdown();
        log.info("{}   - 16 个并发请求在 b1 故障后：成功切到 b2 {} 个，其他明确结果 {} 个",
                BUSINESS, ok, errors);
        assertEquals(threads, ok + errors, "每个请求都必须有明确结果");
        assertTrue(ok >= 1, "至少有请求成功故障切换");
    }

    /** Programmable forwarder: records sends, can simulate transport failure / 5xx per instance. */
    private static final class FakeForwarder extends BackendForwarder {
        private final Map<String, AtomicInteger> sendCounts = new ConcurrentHashMap<>();
        private final Map<String, String> failureMode = new ConcurrentHashMap<>();

        FakeForwarder() {
            super(5000);
        }

        void failTransport(String id) {
            failureMode.put(id, "transport");
        }

        void fail5xx(String id) {
            failureMode.put(id, "5xx");
        }

        void recover(String id) {
            failureMode.remove(id);
        }

        int sentTo(String id) {
            return sendCounts.getOrDefault(id, new AtomicInteger()).get();
        }

        @Override
        public ForwardResponse forward(BackendInstance instance, ForwardRequest request) {
            sendCounts.computeIfAbsent(instance.id(), k -> new AtomicInteger()).incrementAndGet();
            String mode = failureMode.get(instance.id());
            if ("transport".equals(mode)) {
                return ForwardResponse.transportError("simulated connection refused for " + instance.id());
            }
            if ("5xx".equals(mode)) {
                return new ForwardResponse(503, Map.of(),
                        ("{\"error\":\"simulated 503 from " + instance.id() + "\"}").getBytes(),
                        false, null);
            }
            return new ForwardResponse(200, Map.of("Content-Type", List.of("application/json")),
                    ("{\"id\":\"" + instance.id() + "\"}").getBytes(), false, null);
        }
    }
}
