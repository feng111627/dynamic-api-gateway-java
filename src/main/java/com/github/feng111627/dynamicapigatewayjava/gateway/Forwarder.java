package com.github.feng111627.dynamicapigatewayjava.gateway;

import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把请求转发到指定后端实例。
 * 透传语义：请求方法、查询串、业务请求头、请求体原样发给后端；
 * 后端的状态码、响应头、响应体原样带回。
 * 每次调用只发往一个实例；失败返回明确的 ForwardResult.failure，绝不静默丢弃。
 */
public class Forwarder {

    /** 逐跳请求头不透传（由网关与后端之间的连接自己决定）。 */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length", "expect");

    private final HttpClient client;
    private final Duration requestTimeout;

    public Forwarder() {
        this(Duration.ofSeconds(2));
    }

    public Forwarder(Duration timeout) {
        this.requestTimeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    public ForwardResult forward(BackendInstance backend, GatewayRequest req) {
        String target = backend.baseUrl() + req.path()
                + (req.query() == null || req.query().isBlank() ? "" : "?" + req.query());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(requestTimeout);
        // 业务请求头原样透传（跳过逐跳头与 JDK 限制头）
        for (Map.Entry<String, String> h : req.headers().entrySet()) {
            String name = h.getKey().toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(name)) {
                try {
                    builder.header(h.getKey(), h.getValue());
                } catch (IllegalArgumentException ignored) {
                    // JDK 限制的头部名跳过，不影响其余头透传
                }
            }
        }
        // 方法与请求体：有体带体，无体不带；方法名原样使用
        HttpRequest.BodyPublisher publisher = req.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(req.body());
        builder.method(req.method().toUpperCase(Locale.ROOT), publisher);
        try {
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return ForwardResult.ok(resp.statusCode(), resp.body(), resp.headers().map(), backend.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ForwardResult.failure(backend.id(), "interrupted: " + e.getMessage());
        } catch (Exception e) {
            return ForwardResult.failure(backend.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
