package com.github.feng111627.dynamicapigatewayjava.gateway;

import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 把请求转发到指定后端实例。
 * 每次调用只发往一个实例；失败返回明确的 ForwardResult.failure，绝不静默丢弃。
 */
public class Forwarder {

    private final HttpClient client;

    public Forwarder() {
        this(Duration.ofSeconds(2));
    }

    public Forwarder(Duration timeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    public ForwardResult forward(BackendInstance backend, String path, String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(backend.baseUrl() + path))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .build();
        try {
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            return ForwardResult.ok(resp.statusCode(), resp.body(), backend.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ForwardResult.failure(backend.id(), "interrupted: " + e.getMessage());
        } catch (Exception e) {
            return ForwardResult.failure(backend.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
