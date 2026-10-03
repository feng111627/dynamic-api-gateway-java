package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：请求透传——方法/头/查询/体原样到后端，状态码/响应头/体原样回传。 */
@ExtendWith(BusinessCoverageExtension.class)
class PassThroughTest {

    private static final String DOMAIN = "请求透传";

    private record Rig(GatewayService gateway, FakeBackend server) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }

    private static Rig newRig() {
        FakeBackend server = new FakeBackend("backend-1", 0);
        RouteRegistry routes = new RouteRegistry(List.of(new Route("r1", "/api/",
                List.of(new BackendInstance("backend-1", "127.0.0.1", server.port())))));
        GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                new StickySessionStore(60_000), new HealthRegistry(), new Forwarder());
        return new Rig(gateway, server);
    }

    @Test
    @Business(DOMAIN)
    void 请求方法查询串请求头与请求体原样透传() {
        try (Rig rig = newRig()) {
            GatewayRequest req = new GatewayRequest("PUT", "/api/items/42", "flag=1&name=abc",
                    Map.of("X-Custom-Header", "custom-value", "X-Caller-Id", "alice"),
                    "alice", "{\"price\":10}");
            GatewayResponse resp = rig.gateway().handle(req);

            assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind());
            String body = resp.body();
            assertTrue(body.contains("method=PUT"), "方法要透传: " + body);
            assertTrue(body.contains("path=/api/items/42"), "路径要透传: " + body);
            assertTrue(body.contains("query=flag=1&name=abc"), "查询串要透传: " + body);
            assertTrue(body.toLowerCase().contains("header.x-custom-header=custom-value"), "自定义头要透传: " + body);
            assertTrue(body.contains("body={\"price\":10}"), "请求体要透传: " + body);
        }
    }

    @Test
    @Business(DOMAIN)
    void 读取写入更新删除语义不被归一() {
        try (Rig rig = newRig()) {
            for (String method : new String[]{"GET", "POST", "PUT", "DELETE", "PATCH"}) {
                GatewayRequest req = new GatewayRequest(method, "/api/res", null,
                        Map.of(), "caller-" + method,
                        "GET".equals(method) ? null : "payload-" + method);
                GatewayResponse resp = rig.gateway().handle(req);
                assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind(),
                        method + " 应被承接: " + resp.errorDetail());
                assertTrue(resp.body().contains("method=" + method),
                        method + " 不应被归一成别的方法: " + resp.body());
                if (!"GET".equals(method)) {
                    assertTrue(resp.body().contains("body=payload-" + method),
                            method + " 的请求体要透传: " + resp.body());
                }
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 后端状态码响应头与响应体如实带回() {
        try (Rig rig = newRig()) {
            GatewayRequest req = new GatewayRequest("GET",
                    "/api/__respond", "status=418&header=X-Tea-Kind:green&body=teapot",
                    Map.of(), "caller-1", null);
            // /__respond 需要命中 /api/ 前缀路由，FakeBackend 按查询串定制应答
            GatewayResponse resp = rig.gateway().handle(req);

            assertEquals(418, resp.status(), "后端状态码要原样带回");
            assertEquals("teapot", resp.body(), "后端响应体要原样带回");
            // 响应头名大小写不敏感，按小写归一后查找
            Map<String, List<String>> lower = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            lower.putAll(resp.headers());
            assertEquals(List.of("green"), lower.get("X-Tea-Kind"), "后端自定义响应头要原样带回");
            assertEquals("backend-1", resp.backendId());
        }
    }

    @Test
    @Business(DOMAIN)
    void 后端业务错误状态码不被网关改写成成功() {
        try (Rig rig = newRig()) {
            GatewayRequest req = new GatewayRequest("POST", "/api/__respond",
                    "status=409&body=conflict-detail", Map.of(), "caller-1", "x");
            GatewayResponse resp = rig.gateway().handle(req);

            assertEquals(409, resp.status(), "后端的 4xx 是业务结果，不是网关故障");
            assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind());
            assertEquals("conflict-detail", resp.body());
        }
    }
}
