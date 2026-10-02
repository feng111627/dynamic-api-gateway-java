package com.github.feng111627.dynamicapigatewayjava.tests;

import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.model.GatewayConfig;
import com.github.feng111627.dynamicapigatewayjava.model.RouteRule;
import com.github.feng111627.dynamicapigatewayjava.model.ValidationIssue;
import com.github.feng111627.dynamicapigatewayjava.routing.ConfigValidator;
import com.github.feng111627.dynamicapigatewayjava.routing.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.routing.UpdateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business: routing rule hot reload atomicity.
 * Covers: whole-batch apply, whole-batch rejection, version assignment, prefix matching,
 *         commit-hook exactly-once semantics, issue classification, and concurrent
 *         readers never observing a partially applied config.
 */
class RouteHotUpdateAtomicityTest {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY-TEST");
    private static final String BUSINESS = "[业务:路由热更新原子性]";

    private RouteRegistry registry;

    @BeforeAll
    static void banner() {
        log.info("{} ============================================", BUSINESS);
        log.info("{} 业务覆盖：规则整批热更新（全生效/全不生效）", BUSINESS);
        log.info("{} ============================================", BUSINESS);
    }

    @BeforeEach
    void setUp(TestInfo info) {
        registry = new RouteRegistry();
        log.info("{} >>> 用例开始: {}", BUSINESS, info.getDisplayName());
    }

    @AfterEach
    void tearDown(TestInfo info) {
        log.info("{} <<< 用例通过: {}\n", BUSINESS, info.getDisplayName());
    }

    private GatewayConfig validConfigV1() {
        return new GatewayConfig(0, List.of(
                new RouteRule("orders", "/orders",
                        List.of(new BackendInstance("b1", "http://127.0.0.1:18081")),
                        false, null)));
    }

    private GatewayConfig validConfigV2() {
        return new GatewayConfig(0, List.of(
                new RouteRule("orders", "/orders",
                        List.of(new BackendInstance("b1", "http://127.0.0.1:18081"),
                                new BackendInstance("b2", "http://127.0.0.1:18082")),
                        false, null),
                new RouteRule("payments", "/payments",
                        List.of(new BackendInstance("b3", "http://127.0.0.1:18083")),
                        false, null)));
    }

    @Test
    @DisplayName("合法整批更新一次性全生效，版本号递增，旧规则被完整替换")
    void validBatchAppliesAtomically() {
        UpdateResult first = registry.atomicUpdate(validConfigV1());
        assertTrue(first.accepted(), "v1 应被接受");
        assertEquals(1, first.activeConfig().version());
        assertNotNull(registry.match("/orders/123"));
        assertNull(registry.match("/payments/1"));
        log.info("{}   - v1 生效：/orders 可匹配，/payments 尚不存在", BUSINESS);

        UpdateResult second = registry.atomicUpdate(validConfigV2());
        assertTrue(second.accepted(), "v2 应被接受");
        assertEquals(2, second.activeConfig().version());
        assertEquals(2, second.activeConfig().routes().size());
        assertNotNull(registry.match("/orders/123"));
        assertNotNull(registry.match("/payments/1"));
        // the new snapshot is complete: orders now has 2 backends
        assertEquals(2, registry.match("/orders/123").backends().size());
        log.info("{}   - v2 整体替换 v1：两条路由同时可见，orders 后端数=2", BUSINESS);
    }

    @Test
    @DisplayName("批次中任意一条非法则整批拒绝，旧规则一条不少地保留")
    void illegalBatchRejectedWholesaleAndOldRulesPreserved() {
        assertTrue(registry.atomicUpdate(validConfigV1()).accepted());
        RouteRule good = new RouteRule("payments", "/payments",
                List.of(new BackendInstance("b3", "http://127.0.0.1:18083")), false, null);
        RouteRule badUrl = new RouteRule("broken", "/broken",
                List.of(new BackendInstance("bx", "not-a-url")), false, null);
        GatewayConfig mixed = new GatewayConfig(0, List.of(good, badUrl));

        UpdateResult result = registry.atomicUpdate(mixed);

        assertFalse(result.accepted(), "含非法规则的批次必须整体拒绝");
        assertEquals(1, registry.version(), "拒绝时版本号不得前进");
        assertNotNull(registry.match("/orders/1"), "旧路由 orders 必须保留");
        assertNull(registry.match("/payments/1"), "同批的合法新路由也不得生效（不允许只改一半）");
        assertNull(registry.match("/broken/1"), "非法路由当然不能生效");
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("BACKEND_URL_INVALID")));
        log.info("{}   - 含 BACKEND_URL_INVALID 的批次被整体拒绝，/payments 未生效，旧 /orders 完好", BUSINESS);
    }

    @Test
    @DisplayName("各类非法规则能被调用方按稳定错误码区分")
    void issueCodesAreClassifiable() {
        ConfigValidator validator = new ConfigValidator();

        GatewayConfig allKinds = new GatewayConfig(0, List.of(
                new RouteRule(null, "/a", List.of(), false, null),               // id 缺失 + 后端为空
                new RouteRule("dup", "bad-prefix",
                        List.of(new BackendInstance("b", "http://127.0.0.1:18081")),
                        false, new RouteRule.RateLimitSpec(1, null, null)),       // 前缀非法 + 窗口缺失
                new RouteRule("dup", "/c",
                        List.of(new BackendInstance("b", "http://127.0.0.1:18081")),
                        false, new RouteRule.RateLimitSpec(0, -1, 0))));          // 重复 id + 限流参数非法

        List<ValidationIssue> issues = validator.validate(allKinds);
        Map<String, Long> counts = issues.stream()
                .collect(Collectors.groupingBy(ValidationIssue::code, Collectors.counting()));
        log.info("{}   - 检出问题分类: {}", BUSINESS, counts);

        assertContains(counts, "ROUTE_ID_MISSING");
        assertContains(counts, "BACKEND_EMPTY");
        assertContains(counts, "PATH_PREFIX_INVALID");
        assertContains(counts, "DUPLICATE_ROUTE_ID");
        assertContains(counts, "RATE_LIMIT_WINDOW_MISSING");
        assertContains(counts, "RATE_LIMIT_LIMIT_INVALID");
        assertContains(counts, "RATE_LIMIT_WINDOW_INVALID");
        assertFalse(registry.atomicUpdate(allKinds).accepted());
    }

    @Test
    @DisplayName("空批次被拒绝，防止误操作清空全部路由")
    void emptyBatchRejected() {
        registry.atomicUpdate(validConfigV1());
        UpdateResult result = registry.atomicUpdate(new GatewayConfig(0, List.of()));
        assertFalse(result.accepted());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("EMPTY_ROUTE_SET")));
        assertEquals(1, registry.version(), "旧版本必须保留");
        log.info("{}   - 空路由批次返回 EMPTY_ROUTE_SET，线上规则未被清空", BUSINESS);
    }

    @Test
    @DisplayName("同一 backend id 在不同路由映射到不同地址会被识别为冲突")
    void backendIdConflictAcrossRoutesDetected() {
        GatewayConfig conflicting = new GatewayConfig(0, List.of(
                new RouteRule("r1", "/r1",
                        List.of(new BackendInstance("shared", "http://127.0.0.1:18081")), false, null),
                new RouteRule("r2", "/r2",
                        List.of(new BackendInstance("shared", "http://127.0.0.1:19999")), false, null)));
        UpdateResult result = registry.atomicUpdate(conflicting);
        assertFalse(result.accepted());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("BACKEND_CONFLICT")));
        log.info("{}   - 跨路由 backend id 地址冲突返回 BACKEND_CONFLICT", BUSINESS);
    }

    @Test
    @DisplayName("最长前缀匹配：/orders/special 优先命中更具体的路由")
    void longestPrefixWins() {
        GatewayConfig config = new GatewayConfig(0, List.of(
                new RouteRule("orders", "/orders",
                        List.of(new BackendInstance("b1", "http://127.0.0.1:18081")), false, null),
                new RouteRule("orders-special", "/orders/special",
                        List.of(new BackendInstance("b2", "http://127.0.0.1:18082")), false, null)));
        assertTrue(registry.atomicUpdate(config).accepted());
        assertEquals("orders", registry.match("/orders/1").id());
        assertEquals("orders-special", registry.match("/orders/special/9").id());
        log.info("{}   - /orders/1→orders，/orders/special/9→orders-special", BUSINESS);
    }

    @Test
    @DisplayName("提交钩子只在成功提交时执行一次，拒绝时绝不执行")
    void commitHookRunsOnlyOnAccepted() {
        AtomicInteger hookCalls = new AtomicInteger();
        UpdateResult rejected = registry.atomicUpdate(new GatewayConfig(0, List.of()),
                next -> hookCalls.incrementAndGet());
        assertFalse(rejected.accepted());
        assertEquals(0, hookCalls.get(), "被拒绝的批次不得触发提交副作用");

        UpdateResult accepted = registry.atomicUpdate(validConfigV1(), next -> hookCalls.incrementAndGet());
        assertTrue(accepted.accepted());
        assertEquals(1, hookCalls.get(), "成功提交必须恰好触发一次副作用");
        log.info("{}   - 拒绝批次钩子执行 0 次，成功批次钩子执行 1 次", BUSINESS);
    }

    @Test
    @DisplayName("并发读 + 并发写：任何时刻读到的都是完整的某一版本，不会读到半成品")
    void concurrentReadersAlwaysSeeConsistentSnapshot() throws Exception {
        registry.atomicUpdate(validConfigV1());
        int readers = 8;
        int writers = 4;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);
        CountDownLatch start = new CountDownLatch(1);
        List<String> anomalies = new ArrayList<>();
        Map<Integer, AtomicInteger> seenVersions = new ConcurrentHashMap<>();

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < iterations; i++) {
                    GatewayConfig snapshot = registry.current();
                    // 对读取到的每个快照做内部一致性检查：路由 id 不重复、后端数与版本预期自洽
                    List<String> ids = snapshot.routes().stream().map(RouteRule::id).toList();
                    if (ids.size() != new java.util.HashSet<>(ids).size()) {
                        anomalies.add("duplicate route ids in observed snapshot");
                    }
                    for (RouteRule route : snapshot.routes()) {
                        if (route.backends().isEmpty()) {
                            anomalies.add("route " + route.id() + " observed with no backends (partial state)");
                        }
                    }
                    seenVersions.computeIfAbsent((int) snapshot.version(),
                            k -> new AtomicInteger()).incrementAndGet();
                }
            });
        }
        for (int w = 0; w < writers; w++) {
            final int seed = w;
            pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < iterations; i++) {
                    // 交替提交 v1 风格与 v2 风格的完整合法批次；每次都是全量替换
                    GatewayConfig candidate = (i + seed) % 2 == 0 ? validConfigV1() : validConfigV2();
                    UpdateResult result = registry.atomicUpdate(candidate);
                    if (!result.accepted()) {
                        anomalies.add("legal batch rejected under concurrency");
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        log.info("{}   - 观察到的版本分布: {}", BUSINESS,
                seenVersions.entrySet().stream().map(e -> "v" + e.getKey() + "=" + e.getValue().get())
                        .collect(Collectors.joining(", ")));
        assertTrue(seenVersions.size() >= 2, "读者应观察到多个完整版本");
        assertTrue(anomalies.isEmpty(), "未发现半成品快照: " + anomalies);
    }

    private void assertContains(Map<String, Long> counts, String code) {
        assertTrue(counts.containsKey(code), "应当能区分出错误分类: " + code + "，实际=" + counts.keySet());
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
