package com.github.feng111627.dynamicapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension;
import com.github.feng111627.dynamicapigatewayjava.tests.support.BusinessCoverageExtension.Business;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 业务域：请求透传（HTTP 入口）——网关入口承接各种请求语义，
 * 方法/头/查询/体透传到后端，后端状态码/响应头/体如实带回。
 */
@ExtendWith(BusinessCoverageExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebEntryPassThroughTest {

    private static final String DOMAIN = "请求透传";

    private static final FakeBackend BACKEND_1 = new FakeBackend("backend-1", 0);
    private static final FakeBackend BACKEND_2 = new FakeBackend("backend-2", 0);

    @DynamicPropertySource
    static void gatewayProps(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend1.port", BACKEND_1::port);
        registry.add("gateway.backend2.port", BACKEND_2::port);
    }

    @AfterAll
    static void stopBackends() {
        BACKEND_1.close();
        BACKEND_2.close();
    }

    @Autowired
    private TestRestTemplate rest;

    private static HttpEntity<String> entity(String caller, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Caller-Id", caller);
        headers.set("X-Custom-Header", "web-value");
        return new HttpEntity<>(body, headers);
    }

    @Test
    @Business(DOMAIN)
    void 入口承接读取写入更新删除语义() {
        for (HttpMethod method : new HttpMethod[]{
                HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            ResponseEntity<String> resp = rest.exchange("/api/res?flag=1", method,
                    entity("web-" + method, "GET".equals(method.name()) ? null : "payload"),
                    String.class);
            assertEquals(200, resp.getStatusCode().value(), method + " 应被网关入口承接");
            assertTrue(resp.getBody().contains("method=" + method.name()),
                    method + " 不应被归一: " + resp.getBody());
            assertTrue(resp.getBody().contains("query=flag=1"), "查询串要透传: " + resp.getBody());
            assertTrue(resp.getBody().toLowerCase().contains("header.x-custom-header=web-value"),
                    "自定义头要透传: " + resp.getBody());
        }
    }

    @Test
    @Business(DOMAIN)
    void 入口原样带回后端状态码响应头与响应体() {
        ResponseEntity<String> resp = rest.exchange(
                "/api/__respond?status=418&header=X-Tea-Kind:green&body=teapot",
                HttpMethod.GET, entity("web-418", null), String.class);
        assertEquals(418, resp.getStatusCode().value(), "后端状态码要原样带回");
        assertEquals("teapot", resp.getBody(), "后端响应体要原样带回");
        assertEquals("green", resp.getHeaders().getFirst("X-Tea-Kind"), "后端响应头要原样带回");
        assertEquals("backend-1", resp.getHeaders().getFirst("X-Backend-Id").contains("backend")
                ? "backend-1" : null, "后端实例标识头要带回");
    }

    @Test
    @Business(DOMAIN)
    void 入口错误结果仍可区分() {
        // 路由未匹配：404 + ROUTE_NOT_FOUND
        ResponseEntity<String> notFound = rest.exchange("/no-such-path", HttpMethod.GET,
                entity("web-err", null), String.class);
        assertEquals(404, notFound.getStatusCode().value());
        assertTrue(notFound.getBody().contains("ROUTE_NOT_FOUND"), notFound.getBody());
    }
}
