package com.github.feng111627.dynamicapigatewayjava.health;

import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 基于 JDK HttpClient 的健康探测：能建立连接并拿到任何 HTTP 响应即视为存活，
 * 连接/IO 失败视为仍不可用。探测只用于“救活”，不会据此把实例标记为不健康。
 */
public class HttpHealthProbe implements HealthProbe {

    private final HttpClient client;
    private final Duration timeout;
    private final String probePath;

    public HttpHealthProbe(Duration connectTimeout, Duration requestTimeout, String probePath) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.timeout = requestTimeout;
        this.probePath = probePath == null || probePath.isBlank() ? "/" : probePath;
    }

    public HttpHealthProbe() {
        this(Duration.ofMillis(500), Duration.ofSeconds(1), "/");
    }

    @Override
    public boolean isAlive(BackendInstance backend) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(backend.baseUrl() + probePath))
                .timeout(timeout)
                .GET()
                .build();
        try {
            // 能完成一次请求/响应往返（哪怕是 4xx/5xx）都说明进程在、端口通
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
