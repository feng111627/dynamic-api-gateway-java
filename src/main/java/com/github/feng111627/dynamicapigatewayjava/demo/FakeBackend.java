package com.github.feng111627.dynamicapigatewayjava.demo;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地模拟后端：基于 JDK HttpServer 的 echo 服务。
 * 响应里带上自己的实例 id、请求方法、路径、查询串、指定请求头与请求体，
 * 便于验证透传语义与请求落点。
 *
 * 特殊路径：
 *   /__respond?status=418&header=X-Foo:bar&body=hello  —— 按指定状态码/响应头/响应体应答
 * 通用查询参数：
 *   delayMs=300 —— 延迟指定毫秒后再应答（用于并发/容量测试）
 * port 传 0 时随机分配端口。
 */
public class FakeBackend implements AutoCloseable {

    private final String id;
    private final HttpServer server;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    public FakeBackend(String id, int port) {
        this.id = id;
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start fake backend " + id, e);
        }
        server.createContext("/", exchange -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String delay = query.get("delayMs");
                if (delay != null) {
                    Thread.sleep(Long.parseLong(delay));
                }
                String reqBody = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);

                int status = 200;
                String respBody;
                if (exchange.getRequestURI().getPath().endsWith("/__respond")) {
                    // 测试可控应答：状态码/响应头/响应体都由调用方指定
                    status = Integer.parseInt(query.getOrDefault("status", "200"));
                    String header = query.get("header");
                    if (header != null && header.contains(":")) {
                        int i = header.indexOf(':');
                        exchange.getResponseHeaders().add(header.substring(0, i),
                                header.substring(i + 1));
                    }
                    respBody = query.getOrDefault("body", "");
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("backend=").append(id);
                    sb.append(" method=").append(exchange.getRequestMethod());
                    sb.append(" path=").append(exchange.getRequestURI().getPath());
                    if (exchange.getRequestURI().getRawQuery() != null) {
                        sb.append(" query=").append(exchange.getRequestURI().getRawQuery());
                    }
                    sb.append(" body=").append(reqBody);
                    exchange.getRequestHeaders().forEach((name, values) -> {
                        if (name.toLowerCase().startsWith("x-")) {
                            sb.append(" header.").append(name).append("=")
                                    .append(String.join(",", values));
                        }
                    });
                    respBody = sb.toString();
                }
                byte[] bytes = respBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().add("X-Backend-Id", id);
                exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    if (status != 204) {
                        os.write(bytes);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String id() {
        return id;
    }

    /** 历史最大同时在途请求数（容量测试用）。 */
    public int maxInFlight() {
        return maxInFlight.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 本地复现用：java ... FakeBackend <id> <port> */
    public static void main(String[] args) throws Exception {
        String id = args.length > 0 ? args[0] : "backend-1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        FakeBackend backend = new FakeBackend(id, port);
        System.out.println("fake backend " + id + " listening on " + backend.port());
        Thread.currentThread().join();
    }
}
