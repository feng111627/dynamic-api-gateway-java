package com.github.feng111627.dynamicapigatewayjava.gateway;

import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把请求转发到指定后端实例。
 * 每次调用只发往一个实例；失败返回明确的 ForwardResult.failure，绝不静默丢弃。
 *
 * 透传语义：调用方用什么 HTTP 方法、带了哪些请求头、查询参数和请求体，
 * 后端就原样收到（仅去掉逐跳/由 HTTP 客户端重算的头部）；
 * 后端的状态码、响应头、响应体也如实带回，不做任何语义归一。
 */
public class Forwarder {

    /** 逐跳头或由 HttpClient 重算的头：不透传，其余头原样转发。 */
    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding",
            "expect", "upgrade", "keep-alive", "trailer",
            "proxy-connection", "proxy-authenticate", "proxy-authorization");

    private final HttpClient client;
    private final Duration requestTimeout;

    public Forwarder() {
        this(Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    public Forwarder(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
    }

    public ForwardResult forward(BackendInstance backend, ForwardRequest req) {
        String uri = backend.baseUrl() + req.path()
                + (req.query() == null || req.query().isEmpty() ? "" : "?" + req.query());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(requestTimeout);

        copyHeaders(builder, req.headers());

        String method = req.method() == null ? "GET" : req.method().toUpperCase(Locale.ROOT);
        boolean noBody = req.body() == null;
        HttpRequest.BodyPublisher publisher = noBody
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(req.body());
        builder.method(method, publisher);

        try {
            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>(resp.headers().map());
            return ForwardResult.ok(resp.statusCode(), responseHeaders, resp.body(), backend.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ForwardResult.failure(backend.id(), "interrupted: " + e.getMessage());
        } catch (Exception e) {
            return ForwardResult.failure(backend.id(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void copyHeaders(HttpRequest.Builder builder, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((name, values) -> {
            if (name == null || SKIP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            if (values == null) {
                return;
            }
            for (String v : values) {
                if (v != null) {
                    builder.header(name, v);
                }
            }
        });
    }
}
