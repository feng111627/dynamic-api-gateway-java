package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** 业务域：请求透传——HTTP 方法/请求头/查询参数/请求体与后端状态码/响应头/响应体原样往来。 */
@ExtendWith(BusinessCoverageExtension.class)
class RequestPassthroughTest {

    private static final String DOMAIN = "请求透传";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Rig(FakeBackend server, GatewayService gateway) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }

    private static Rig newRig() {
        FakeBackend server = new FakeBackend("backend-1", 0);
        BackendInstance b1 = new BackendInstance("backend-1", "127.0.0.1", server.port());
        RouteRegistry routes = new RouteRegistry(List.of(new Route("r1", "/api/", List.of(b1))));
        GatewayService gateway = new GatewayService(routes, new RateLimiter(List.of()),
                new StickySessionStore(60_000), new HealthRegistry(), new Forwarder());
        return new Rig(server, gateway);
    }

    private static GatewayRequest req(String path, String method,
                                      Map<String, List<String>> headers,
                                      String query, String body) {
        return new GatewayRequest(path, method, headers, query, body);
    }

    private static JsonNode echoBody(GatewayResponse resp) throws Exception {
        assertEquals(GatewayResponse.ErrorKind.NONE, resp.errorKind(),
                "透传失败不应是网关错误: " + resp.errorDetail());
        assertNotNull(resp.body());
        return JSON.readTree(resp.body());
    }

    /** 在回显 JSON 的 headers 对象里按小写名找值（HTTP 头名大小写不敏感）。 */
    private static String headerEcho(JsonNode echo, String name) {
        JsonNode headers = echo.path("headers");
        String lower = name.toLowerCase(Locale.ROOT);
        var fields = headers.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            if (e.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                if (e.getValue().isArray()) {
                    StringBuilder joined = new StringBuilder();
                    e.getValue().forEach(v -> {
                        if (joined.length() > 0) {
                            joined.append(',');
                        }
                        joined.append(v.asText());
                    });
                    return joined.toString();
                }
                return e.getValue().asText();
            }
        }
        return null;
    }

    @Test
    @Business(DOMAIN)
    void 常见HTTP方法原样透传不归一() throws Exception {
        try (Rig rig = newRig()) {
            for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH")) {
                String body = "GET".equals(method) ? null : ("body-for-" + method);
                GatewayResponse resp = rig.gateway().handle(
                        req("/api/hello", method, Map.of(), null, body), "c1");
                JsonNode echo = echoBody(resp);
                assertEquals(method, echo.path("method").asText(),
                        "后端必须收到与调用方一致的方法，不能被归一");
                if (body != null) {
                    assertEquals(body, echo.path("body").asText(), "请求体必须原样到达后端");
                }
            }
        }
    }

    @Test
    @Business(DOMAIN)
    void 请求头原样透传() throws Exception {
        try (Rig rig = newRig()) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("X-Custom-One", List.of("value-1"));
            headers.put("X-Caller-Id", List.of("alice"));
            headers.put("X-Multi", List.of("mv1", "mv2"));
            GatewayResponse resp = rig.gateway().handle(
                    req("/api/hello", "POST", headers, null, "hello"), "alice");
            JsonNode echo = echoBody(resp);
            assertEquals("value-1", headerEcho(echo, "X-Custom-One"), "自定义头必须透传");
            assertEquals("alice", headerEcho(echo, "X-Caller-Id"), "调用方标识头必须透传");
            // 多值头：HttpServer 可能合并为逗号串或保序回显，至少两个值都要能看到
            JsonNode headersNode = echo.path("headers");
            String multi = headerEcho(echo, "X-Multi");
            assertNotNull(multi);
            assertTrue(multi.contains("mv1") && multi.contains("mv2"), "多值头内容不能丢");
            assertTrue(headersNode.size() >= 3, "头数量不应莫名减少: " + headersNode);
        }
    }

    @Test
    @Business(DOMAIN)
    void 查询参数原样透传() throws Exception {
        try (Rig rig = newRig()) {
            // 真实调用方发出的原始查询串本就是百分号编码的，网关必须原样转发不改写、不丢空值
            String query = "a=1&b=two%20x&z=&cn=%E4%B8%AD%E6%96%87";
            GatewayResponse resp = rig.gateway().handle(
                    req("/api/hello", "GET", Map.of(), query, null), "c1");
            JsonNode echo = echoBody(resp);
            assertEquals(query, echo.path("query").asText(), "原始查询串必须原样到达后端");
        }
    }

    @Test
    @Business(DOMAIN)
    void 请求体原样透传() throws Exception {
        try (Rig rig = newRig()) {
            String body = "{\"order\":123,\"note\":\"透传不改动\"}";
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("Content-Type", List.of("application/json"));
            GatewayResponse resp = rig.gateway().handle(
                    req("/api/orders", "POST", headers, null, body), "c1");
            JsonNode echo = echoBody(resp);
            assertEquals(body, echo.path("body").asText(), "请求体必须字节语义一致地透传");
            assertEquals("/api/orders", echo.path("path").asText());
        }
    }

    @Test
    @Business(DOMAIN)
    void 后端状态码与响应头如实回传() {
        try (Rig rig = newRig()) {
            GatewayResponse created = rig.gateway().handle(
                    req("/api/status/201", "POST", Map.of(), null, "x"), "c1");
            assertEquals(201, created.status(), "后端 201 必须如实回传，不能被改成 200");
            assertEquals(GatewayResponse.ErrorKind.NONE, created.errorKind());
            assertEquals("backend-1", created.headers().getOrDefault("x-backend-id", List.of())
                    .stream().findFirst().orElse(null), "后端响应头 X-Backend-Id 要回传");
            assertEquals("201", created.headers().getOrDefault("x-backend-status-hint", List.of())
                    .stream().findFirst().orElse(null));

            GatewayResponse notFound = rig.gateway().handle(
                    req("/api/status/404", "GET", Map.of(), null, null), "c1");
            assertEquals(404, notFound.status(), "后端业务 404 必须原样回传");
            assertEquals(GatewayResponse.ErrorKind.NONE, notFound.errorKind(),
                    "后端返回 4xx/5xx 是正常透传结果，不应被网关当成故障切换");
        }
    }

    @Test
    @Business(DOMAIN)
    void 响应体原样回传() throws Exception {
        try (Rig rig = newRig()) {
            GatewayResponse resp = rig.gateway().handle(
                    req("/api/hello", "POST", Map.of(), "q=1", "abc"), "c1");
            JsonNode echo = echoBody(resp);
            assertEquals("backend-1", echo.path("backend").asText());
            assertEquals("/api/hello", echo.path("path").asText());
            assertEquals("abc", echo.path("body").asText());
        }
    }
}
