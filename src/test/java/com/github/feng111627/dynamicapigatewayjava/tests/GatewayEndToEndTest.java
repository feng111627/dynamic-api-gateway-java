package com.github.feng111627.dynamicapigatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business: end-to-end behaviour over real HTTP against the in-process backend simulator.
 * Covers: hot reload atomicity via admin API (200 / 422 / 400 classification), real 503
 *         failover of a sticky session, active-probe recovery + backoff reattach, and a
 *         rate-limit rejection carrying the rule identity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.backend-simulator.enabled=true",
        "gateway.backend-simulator.instances=2",
        "gateway.backend-simulator.base-port=19081",
        "gateway.health-probe-interval-ms=200",
        "gateway.sticky-reattach-backoff-ms=300",
        "gateway.sticky-ttl-ms=60000",
        "gateway.unhealthy-threshold=1",
        "gateway.healthy-threshold=1"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY-TEST");
    private static final String BUSINESS = "[业务:端到端HTTP链路]";

    @LocalServerPort
    private int port;

    private final TestRestTemplate http = new TestRestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private String gatewayUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String backendUrl(int n, String path) {
        return "http://127.0.0.1:" + (19080 + n) + path;
    }

    @BeforeAll
    static void banner() {
        log.info("{} ============================================", BUSINESS);
        log.info("{} 业务覆盖：真实 HTTP 热更新 / 故障切换 / 恢复回切 / 限流", BUSINESS);
        log.info("{} ============================================", BUSINESS);
    }

    @Test
    @Order(1)
    @DisplayName("启动后演示路由已原子加载，/orders 可转发到模拟器")
    void demoConfigLoadedAndForwardingWorks() throws Exception {
        log.info("{} >>> 用例开始: 启动后演示路由加载并可转发", BUSINESS);
        JsonNode config = getJson("/admin/config");
        assertEquals(1, config.get("version").asInt());
        assertEquals(2, config.get("routes").size());
        log.info("{}   - GET /admin/config: version=1 routes={}", BUSINESS,
                config.get("routes").findValuesAsText("id"));

        ResponseEntity<String> resp = http.exchange(gatewayUrl("/orders/1"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders("e2e-a")), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode body = mapper.readTree(resp.getBody());
        String instance = body.get("id").asText();
        assertTrue(instance.startsWith("backend-"));
        assertEquals(instance, resp.getHeaders().getFirst("X-Gateway-Instance"));
        log.info("{}   - GET /orders/1 转发成功，命中实例 {}", BUSINESS,
                resp.getHeaders().getFirst("X-Gateway-Instance"));
        log.info("{} <<< 用例通过: 启动后演示路由加载并可转发\n", BUSINESS);
    }

    @Test
    @Order(2)
    @DisplayName("粘性：同调用方连续请求落在同一实例")
    void stickyOverHttp() throws Exception {
        log.info("{} >>> 用例开始: HTTP 粘性保持", BUSINESS);
        String first = requestInstance("/orders/sticky", "e2e-sticky");
        String second = requestInstance("/orders/sticky", "e2e-sticky");
        String third = requestInstance("/orders/sticky", "e2e-sticky");
        assertEquals(first, second);
        assertEquals(second, third);
        log.info("{}   - 连续 3 次均落在 {}", BUSINESS, first);
        log.info("{} <<< 用例通过: HTTP 粘性保持\n", BUSINESS);
    }

    @Test
    @Order(3)
    @DisplayName("真实故障切换与恢复回切：强制绑定实例返回 503，流量切到另一实例，恢复后切回")
    void realFailoverAndRecoveryOverHttp() throws Exception {
        log.info("{} >>> 用例开始: 真实 503 故障切换", BUSINESS);
        // fresh caller binds to whichever instance round-robin picks first
        String caller = "e2e-failover";
        ResponseEntity<String> boundResp = http.exchange(gatewayUrl("/orders/fail"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders(caller)), String.class);
        assertEquals(HttpStatus.OK, boundResp.getStatusCode());
        String primary = mapper.readTree(boundResp.getBody()).get("id").asText();
        String secondary = "backend-1".equals(primary) ? "backend-2" : "backend-1";
        int primaryIndex = "backend-1".equals(primary) ? 1 : 2;
        log.info("{}   - 调用方初始绑定 {}，故障后应切换到 {}", BUSINESS, primary, secondary);

        forceBackendState(primaryIndex, "down");
        try {
            ResponseEntity<String> resp = http.exchange(gatewayUrl("/orders/fail"),
                    HttpMethod.GET, new HttpEntity<>(callerHeaders(caller)), String.class);
            assertEquals(HttpStatus.OK, resp.getStatusCode(), "故障实例必须在同请求内切走并成功");
            String instance = resp.getHeaders().getFirst("X-Gateway-Instance");
            assertEquals(secondary, instance);
            assertEquals(primary + "," + secondary, resp.getHeaders().getFirst("X-Gateway-Attempted"),
                    "响应头需展示尝试序列：故障实例仅一次");
            log.info("{}   - {} 强制 down 后请求被切到 {}，尝试序列={}",
                    BUSINESS, primary, secondary, resp.getHeaders().getFirst("X-Gateway-Attempted"));

            // next request stays on the secondary without touching the dead one
            assertEquals(secondary, requestInstance("/orders/fail", caller));
            assertTrue(waitForHealth(primary, "UNHEALTHY", 3000));
            log.info("{}   - 管理端健康观察到 {}=UNHEALTHY，后续稳定在 {}", BUSINESS, primary, secondary);

            forceBackendState(primaryIndex, "up");
            assertTrue(waitForHealth(primary, "HEALTHY", 5000),
                    "主动探活应在 " + primary + " 恢复后把它标记为 HEALTHY");
            log.info("{}   - {} 恢复上线，主动探活已标记 HEALTHY", BUSINESS, primary);

            // backoff 300ms; send a few requests; the sticky session must reattach to primary
            String reattached = null;
            for (int i = 0; i < 10; i++) {
                Thread.sleep(200);
                reattached = requestInstance("/orders/fail", caller);
                if (primary.equals(reattached)) {
                    break;
                }
            }
            assertEquals(primary, reattached, "冷却期后粘性会话应回切到原实例 " + primary);
            assertEquals(primary, requestInstance("/orders/fail", caller), "回切后保持稳定");
            log.info("{}   - 回切冷却后，调用方粘性回到 {} 并保持稳定", BUSINESS, primary);
        } finally {
            forceBackendState(primaryIndex, "up");
        }
        log.info("{} <<< 用例通过: 真实 503 故障切换与恢复回切\n", BUSINESS);
    }

    @Test
    @Order(4)
    @DisplayName("连接被拒绝（实例直接消失）同样故障切换")
    void connectionRefusedFailoverOverHttp() throws Exception {
        log.info("{} >>> 用例开始: 连接拒绝型故障切换", BUSINESS);
        String body = """
                {
                  "routes": [
                    {"id":"orders","pathPrefix":"/orders","stickySession":true,
                     "backends":[
                       {"id":"backend-1","baseUrl":"http://127.0.0.1:19081"},
                       {"id":"backend-2","baseUrl":"http://127.0.0.1:19082"}
                     ]},
                    {"id":"dead-route","pathPrefix":"/dead","stickySession":true,
                     "backends":[
                       {"id":"dead","baseUrl":"http://127.0.0.1:1"},
                       {"id":"ghost","baseUrl":"http://127.0.0.1:1"}
                     ]}
                  ]
                }""";
        HttpHeaders headers = jsonHeaders();
        ResponseEntity<String> put = http.exchange(gatewayUrl("/admin/config"),
                HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, put.getStatusCode());
        JsonNode resp = mapper.readTree(put.getBody());
        assertTrue(resp.get("accepted").asBoolean());
        log.info("{}   - 热更新加入 /dead 路由（dead→无监听端口，alive→backend-2）version={}",
                BUSINESS, resp.get("version"));

        // Both backends refuse connections: the request must still end with an explicit
        // result (502 ALL_INSTANCES_FAILED), having tried each instance exactly once.
        ResponseEntity<String> result = http.exchange(gatewayUrl("/dead/x"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders("e2e-dead")), String.class);
        assertEquals(HttpStatus.BAD_GATEWAY, result.getStatusCode());
        JsonNode resultBody = mapper.readTree(result.getBody());
        assertEquals("ALL_INSTANCES_FAILED", resultBody.get("code").asText());
        List<String> attempted = new java.util.ArrayList<>();
        resultBody.get("attemptedInstances").forEach(n -> attempted.add(n.asText()));
        assertEquals(2, attempted.size());
        assertEquals(new java.util.HashSet<>(List.of("dead", "ghost")),
                new java.util.HashSet<>(attempted), "每个实例恰好尝试一次，不重发不静默丢弃");
        assertEquals(attempted.size(), attempted.stream().distinct().count(), "单次请求内不得重复发送");
        log.info("{}   - dead/ghost 均连接被拒→502 ALL_INSTANCES_FAILED，尝试序列={}",
                BUSINESS, attempted);
        log.info("{} <<< 用例通过: 连接拒绝型故障切换\n", BUSINESS);
    }

    @Test
    @Order(5)
    @DisplayName("热更新：非法批次整体拒绝（422 且带错误码），数据面继续用旧规则；合法批次后新规则生效")
    void hotUpdateRejectionAndApplicationOverHttp() throws Exception {
        log.info("{} >>> 用例开始: 热更新拒绝与生效语义", BUSINESS);
        String illegal = """
                {
                  "routes": [
                    {"id":"new-route","pathPrefix":"/new","stickySession":false,
                     "backends":[{"id":"b","baseUrl":"http://127.0.0.1:19081"}]},
                    {"id":"bad","pathPrefix":"bad-no-slash","stickySession":false,
                     "backends":[{"id":"b","baseUrl":"garbage"}]}
                  ]
                }""";
        ResponseEntity<String> rejected = http.exchange(gatewayUrl("/admin/config"),
                HttpMethod.PUT, new HttpEntity<>(illegal, jsonHeaders()), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatusCode());
        JsonNode rejBody = mapper.readTree(rejected.getBody());
        assertFalse(rejBody.get("accepted").asBoolean());
        assertTrue(rejBody.get("issues").toString().contains("PATH_PREFIX_INVALID"));
        assertTrue(rejBody.get("issues").toString().contains("BACKEND_URL_INVALID"));
        log.info("{}   - 非法批次返回 422，问题码包含 PATH_PREFIX_INVALID / BACKEND_URL_INVALID，旧配置保留",
                BUSINESS);

        // old rules still serve traffic; the good route from the rejected batch never appeared
        assertEquals(HttpStatus.OK, http.exchange(gatewayUrl("/orders/still"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders("x")), String.class).getStatusCode());

        String legal = """
                {
                  "routes": [
                    {"id":"fresh","pathPrefix":"/fresh","stickySession":false,
                     "backends":[{"id":"backend-2","baseUrl":"http://127.0.0.1:19082"}]}
                  ]
                }""";
        ResponseEntity<String> accepted = http.exchange(gatewayUrl("/admin/config"),
                HttpMethod.PUT, new HttpEntity<>(legal, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, accepted.getStatusCode());

        ResponseEntity<String> fresh = http.exchange(gatewayUrl("/fresh/x"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders("x")), String.class);
        assertEquals(HttpStatus.OK, fresh.getStatusCode());
        assertEquals("backend-2", mapper.readTree(fresh.getBody()).get("id").asText());

        ResponseEntity<String> oldGone = http.exchange(gatewayUrl("/orders/gone"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders("x")), String.class);
        assertEquals(HttpStatus.NOT_FOUND, oldGone.getStatusCode(), "旧规则已被整批替换");
        log.info("{}   - 合法批次生效：/fresh 可转发，旧 /orders 已完全下线（全量替换语义）", BUSINESS);
        log.info("{} <<< 用例通过: 热更新拒绝与生效语义\n", BUSINESS);
    }

    @Test
    @Order(6)
    @DisplayName("请求体非法返回 400 REQUEST_BODY_INVALID，与规则非法 422 区分")
    void malformedBodyIs400Not422() {
        log.info("{} >>> 用例开始: 请求体非法分类", BUSINESS);
        ResponseEntity<String> resp = http.exchange(gatewayUrl("/admin/config"),
                HttpMethod.PUT, new HttpEntity<>("{not-json", jsonHeaders()), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("REQUEST_BODY_INVALID"));
        log.info("{}   - 坏 JSON 返回 400/REQUEST_BODY_INVALID，区别于 422 规则非法", BUSINESS);
        log.info("{} <<< 用例通过: 请求体非法分类\n", BUSINESS);
    }

    @Test
    @Order(7)
    @DisplayName("路由级限流：低配额路由超限返回 429，响应头/体标明命中的规则维度")
    void rateLimitRejectionOverHttp() throws Exception {
        log.info("{} >>> 用例开始: HTTP 429 限流拒绝", BUSINESS);
        String config = """
                {
                  "routes": [
                    {"id":"tight","pathPrefix":"/tight","stickySession":false,
                     "rateLimit":{"routeLimit":2,"windowSeconds":10},
                     "backends":[{"id":"backend-1","baseUrl":"http://127.0.0.1:19081"}]}
                  ]
                }""";
        ResponseEntity<String> put = http.exchange(gatewayUrl("/admin/config"),
                HttpMethod.PUT, new HttpEntity<>(config, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, put.getStatusCode());

        assertEquals(HttpStatus.OK, http.exchange(gatewayUrl("/tight/1"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders("c1")), String.class).getStatusCode());
        assertEquals(HttpStatus.OK, http.exchange(gatewayUrl("/tight/2"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders("c2")), String.class).getStatusCode());
        ResponseEntity<String> third = http.exchange(gatewayUrl("/tight/3"),
                HttpMethod.GET, new HttpEntity<>(callerHeaders("c3")), String.class);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getStatusCode());
        assertEquals("ROUTE", third.getHeaders().getFirst("X-RateLimit-Scope"));
        assertEquals("tight", third.getHeaders().getFirst("X-RateLimit-Route"));
        JsonNode body = mapper.readTree(third.getBody());
        assertEquals("RATE_LIMITED", body.get("code").asText());
        assertEquals("ROUTE", body.get("rateLimitRule").get("scope").asText());
        assertEquals(3, body.get("rateLimitRule").get("used").asInt());
        log.info("{}   - 配额 2/10s：第 3 个请求 429，X-RateLimit-Scope=ROUTE route=tight used=3", BUSINESS);
        log.info("{} <<< 用例通过: HTTP 429 限流拒绝\n", BUSINESS);
    }

    // ---------- helpers ----------

    private JsonNode getJson(String path) throws Exception {
        return mapper.readTree(http.getForObject(gatewayUrl(path), String.class));
    }

    private HttpHeaders callerHeaders(String caller) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Caller-Id", caller);
        return h;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String requestInstance(String path, String caller) throws Exception {
        ResponseEntity<String> resp = http.exchange(gatewayUrl(path),
                HttpMethod.GET, new HttpEntity<>(callerHeaders(caller)), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "body=" + resp.getBody());
        return mapper.readTree(resp.getBody()).get("id").asText();
    }

    private void forceBackendState(int n, String state) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        http.exchange(backendUrl(n, "/control"), HttpMethod.POST,
                new HttpEntity<>(state, h), String.class);
    }

    private boolean waitForHealth(String instance, String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JsonNode health = getJson("/admin/health");
            for (JsonNode row : health.get("instances")) {
                if (instance.equals(row.get("id").asText())
                        && expected.equals(row.get("status").asText())) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }
}
