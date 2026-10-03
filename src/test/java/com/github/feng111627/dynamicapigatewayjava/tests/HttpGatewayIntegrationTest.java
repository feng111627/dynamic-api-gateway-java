package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import com.github.feng111627.dynamicapigatewayjava.Application;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension.Business;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 真实 HTTP 端到端：起一个监听随机端口的网关 + 两个模拟后端，
 * 验证全方法承接、透传、状态码/响应头回传、管理端健康观测。
 */
@ExtendWith(BusinessCoverageExtension.class)
class HttpGatewayIntegrationTest {

    private static final String DOMAIN = "端到端透传";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static FakeBackend backend1;
    private static FakeBackend backend2;
    private static ConfigurableApplicationContext app;
    private static int gatewayPort;

    @BeforeAll
    static void start() throws Exception {
        backend1 = new FakeBackend("backend-1", 0);
        backend2 = new FakeBackend("backend-2", 0);
        app = new SpringApplication(Application.class)
                .run(
                        "--server.port=0",
                        "--gateway.backend1.port=" + backend1.port(),
                        "--gateway.backend2.port=" + backend2.port(),
                        "--gateway.session.ttl-millis=300000",
                        "--gateway.recovery.probe-interval-millis=600000",
                        "--spring.task.scheduling.enabled=false");
        gatewayPort = ((org.springframework.boot.web.servlet.context
                .ServletWebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (backend1 != null) {
            backend1.close();
        }
        if (backend2 != null) {
            backend2.close();
        }
    }

    private static HttpResponse<String> send(String method, String pathAndQuery,
                                            String body, String... headers) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + gatewayPort + pathAndQuery));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        b.method(method, pub);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Business(DOMAIN)
    void 入口承接GET并原样透传查询串() throws Exception {
        HttpResponse<String> resp = send("GET", "/api/hello?a=1&b=2", null,
                "X-Caller-Id", "intg-get");
        assertEquals(200, resp.statusCode());
        assertEquals("backend-1", resp.headers().firstValue("x-backend-id").orElse(null),
                "后端响应头要回传");
        JsonNode echo = JSON.readTree(resp.body());
        assertEquals("GET", echo.path("method").asText());
        assertEquals("a=1&b=2", echo.path("query").asText());
    }

    @Test
    @Business(DOMAIN)
    void 入口承接PUT_DELETE且请求体与自定义头透传() throws Exception {
        HttpResponse<String> put = send("PUT", "/api/items/7", "payload-put",
                "X-Caller-Id", "intg-write", "X-Custom", "cv");
        assertEquals(200, put.statusCode());
        JsonNode echo = JSON.readTree(put.body());
        assertEquals("PUT", echo.path("method").asText());
        assertEquals("payload-put", echo.path("body").asText());
        assertEquals("cv", echo.path("headers").path("X-custom").asText(),
                "HTTP 头大小写不敏感，自定义头必须透传");

        HttpResponse<String> del = send("DELETE", "/api/items/9", null,
                "X-Caller-Id", "intg-write");
        assertEquals(200, del.statusCode());
        assertEquals("DELETE", JSON.readTree(del.body()).path("method").asText());
    }

    @Test
    @Business(DOMAIN)
    void 表单请求体在PUT_DELETE上也原样透传() throws Exception {
        // Spring 会把 application/x-www-form-urlencoded 的非 POST 当表单提前消费 body，
        // RawBodyCachingFilter 必须保证原始请求体仍被透传
        HttpResponse<String> put = send("PUT", "/api/items/8", "form-a=1&form-b=two",
                "Content-Type", "application/x-www-form-urlencoded",
                "X-Caller-Id", "intg-form");
        assertEquals(200, put.statusCode());
        JsonNode echo = JSON.readTree(put.body());
        assertEquals("PUT", echo.path("method").asText());
        assertEquals("form-a=1&form-b=two", echo.path("body").asText(),
                "表单编码的 PUT 请求体不能被表单解析吞掉");

        HttpResponse<String> del = send("DELETE", "/api/items/8", "gone=yes",
                "Content-Type", "application/x-www-form-urlencoded",
                "X-Caller-Id", "intg-form");
        assertEquals(200, del.statusCode());
        assertEquals("gone=yes", JSON.readTree(del.body()).path("body").asText());
    }

    @Test
    @Business(DOMAIN)
    void 后端状态码响应头响应体如实回传不归一() throws Exception {
        HttpResponse<String> resp = send("POST", "/api/status/201", "x",
                "X-Caller-Id", "intg-status");
        assertEquals(201, resp.statusCode(), "后端 201 不能被网关改成 200");
        assertEquals("201", resp.headers().firstValue("x-backend-status-hint").orElse(null));
        assertNotNull(resp.body());
        assertTrue(resp.body().contains("\"backend\":\"backend"));
    }

    @Test
    @Business(DOMAIN)
    void 无路由返回404并带可区分错误头() throws Exception {
        HttpResponse<String> resp = send("GET", "/no/such/route", null);
        assertEquals(404, resp.statusCode());
        assertEquals("ROUTE_NOT_FOUND", resp.headers().firstValue("x-gateway-error-kind").orElse(null));
        assertTrue(resp.body().contains("ROUTE_NOT_FOUND"));
    }

    @Test
    @Business(DOMAIN)
    void 管理端健康状态可观察() throws Exception {
        HttpResponse<String> resp = send("GET", "/admin/health", null);
        assertEquals(200, resp.statusCode());
        JsonNode body = JSON.readTree(resp.body());
        assertTrue(body.path("routes").isArray());
        assertEquals(0, body.path("unhealthy").size(), "初始两个后端都健康");

        // 管理端列路由也应可用
        HttpResponse<String> routes = send("GET", "/admin/routes", null);
        assertEquals(200, routes.statusCode());
        JsonNode arr = JSON.readTree(routes.body());
        assertTrue(arr.isArray() && arr.size() >= 1);
    }
}
