package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.route.RouteUpdateException;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension.Business;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：路由热更新——整批原子生效、非法整体拒绝、并发读不见中间态。 */
@ExtendWith(BusinessCoverageExtension.class)
class RouteHotUpdateTest {

    private static final String DOMAIN = "路由热更新";

    private static Route route(String id, String prefix, String backendId) {
        return new Route(id, prefix, List.of(new BackendInstance(backendId, "127.0.0.1", 18081)));
    }

    @Test
    @Business(DOMAIN)
    void 合法批次整体生效() {
        RouteRegistry registry = new RouteRegistry(List.of(route("r1", "/api/a/", "b1")));
        registry.updateRoutes(List.of(route("r2", "/api/b/", "b2"), route("r3", "/api/c/", "b3")));

        assertTrue(registry.match("/api/a/x").isEmpty(), "旧路由应整批消失");
        assertEquals("r2", registry.match("/api/b/x").orElseThrow().id());
        assertEquals("r3", registry.match("/api/c/x").orElseThrow().id());
    }

    @Test
    @Business(DOMAIN)
    void 非法批次整体拒绝且旧表保留() {
        RouteRegistry registry = new RouteRegistry(List.of(route("r1", "/api/a/", "b1")));
        List<Route> badBatch = List.of(
                route("ok", "/api/ok/", "b1"),
                route("bad-prefix", "no-slash", "b1"),          // 非法前缀
                new Route("no-backend", "/api/nb/", List.of()), // 无后端
                route("ok", "/api/dup/", "b1"));                // 重复路由 id

        RouteUpdateException ex = assertThrows(RouteUpdateException.class,
                () -> registry.updateRoutes(badBatch));

        // 调用方可区分问题类型
        Set<RouteUpdateException.ErrorType> types = ex.getFailures().stream()
                .map(RouteUpdateException.Failure::type)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(types.contains(RouteUpdateException.ErrorType.INVALID_PATH_PREFIX));
        assertTrue(types.contains(RouteUpdateException.ErrorType.EMPTY_BACKENDS));
        assertTrue(types.contains(RouteUpdateException.ErrorType.DUPLICATE_ROUTE_ID));

        // 旧表原样保留：合法的那条也没有生效
        assertEquals("r1", registry.match("/api/a/x").orElseThrow().id());
        assertTrue(registry.match("/api/ok/x").isEmpty());
    }

    @Test
    @Business(DOMAIN)
    void 并发更新与读取不出现中间态() throws Exception {
        RouteRegistry registry = new RouteRegistry(List.of(route("v0", "/api/v0/", "b1")));
        int writers = 4;
        int readers = 8;
        int rounds = 200;
        CountDownLatch start = new CountDownLatch(1);
        Set<String> anomalies = ConcurrentHashMap.newKeySet();

        Thread[] threads = new Thread[writers + readers];
        for (int i = 0; i < writers; i++) {
            int v = i + 1;
            threads[i] = new Thread(() -> {
                await(start);
                for (int r = 0; r < rounds; r++) {
                    registry.updateRoutes(List.of(route("v" + v, "/api/v" + v + "/", "b1")));
                }
            });
        }
        for (int i = 0; i < readers; i++) {
            threads[writers + i] = new Thread(() -> {
                await(start);
                for (int r = 0; r < rounds * writers; r++) {
                    // 每次读到的表必须自洽：要么匹配到某个完整版本，要么暂时没有
                    registry.snapshot().routes().forEach(route -> {
                        if (!route.id().matches("v\\d+")) {
                            anomalies.add("corrupt route id: " + route.id());
                        }
                    });
                }
            });
        }
        for (Thread t : threads) t.start();
        start.countDown();
        for (Thread t : threads) t.join(30_000);

        assertTrue(anomalies.isEmpty(), "并发读写出现中间态: " + anomalies);
    }

    @Test
    @Business(DOMAIN)
    void 空批次被拒绝() {
        RouteRegistry registry = new RouteRegistry(List.of(route("r1", "/api/a/", "b1")));
        RouteUpdateException ex = assertThrows(RouteUpdateException.class,
                () -> registry.updateRoutes(List.of()));
        assertEquals(RouteUpdateException.ErrorType.EMPTY_BATCH, ex.getFailures().get(0).type());
        assertEquals("r1", registry.match("/api/a/x").orElseThrow().id());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
