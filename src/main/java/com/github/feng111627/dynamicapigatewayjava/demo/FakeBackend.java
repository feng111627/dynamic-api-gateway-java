package com.github.feng111627.dynamicapigatewayjava.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地模拟后端：基于 JDK HttpServer。
 * - 响应里回显实例 id、请求方法、路径、查询串、请求头、请求体，便于验证透传；
 * - 路径最后一段为纯数字（如 /status/201、/status/404）时，用该状态码并带上自定义响应头，
 *   用于验证状态码/响应头的原样回传；
 * - /hold/{id} 在服务端挂起请求（直到 releaseAllHolds），用于验证并发上限；
 * - port 传 0 时随机分配端口。
 */
public class FakeBackend implements AutoCloseable {

    private final String id;
    private final HttpServer server;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private volatile CountDownLatch releaseGate = new CountDownLatch(0);

    public FakeBackend(String id, int port) {
        this.id = id;
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start fake backend " + id, e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        int cur = active.incrementAndGet();
        peak.accumulateAndGet(cur, Math::max);
        try {
            String path = exchange.getRequestURI().getPath();

            if (path.contains("/hold/")) {
                try {
                    releaseGate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                writeJson(exchange, 200, "{\"backend\":\"" + id + "\",\"held\":true}");
                return;
            }

            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = parseTrailingStatus(path);
            String method = exchange.getRequestMethod();
            String query = exchange.getRequestURI().getRawQuery();

            StringBuilder sb = new StringBuilder();
            sb.append("{\"backend\":\"").append(id).append('"');
            sb.append(",\"method\":\"").append(escape(method)).append('"');
            sb.append(",\"path\":\"").append(escape(path)).append('"');
            sb.append(",\"query\":\"").append(escape(query == null ? "" : query)).append('"');
            sb.append(",\"body\":\"").append(escape(reqBody)).append('"');
            sb.append(",\"headers\":{");
            boolean first = true;
            for (Map.Entry<String, java.util.List<String>> e : exchange.getRequestHeaders().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":");
                java.util.List<String> vals = e.getValue();
                if (vals.size() == 1) {
                    sb.append("\"").append(escape(vals.get(0))).append("\"");
                } else {
                    sb.append('[');
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append("\"").append(escape(vals.get(i))).append("\"");
                    }
                    sb.append(']');
                }
            }
            sb.append("}}");
            writeJson(exchange, status, sb.toString());
        } finally {
            active.decrementAndGet();
        }
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("X-Backend-Id", id);
        exchange.getResponseHeaders().add("X-Backend-Status-Hint", String.valueOf(status));
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static int parseTrailingStatus(String path) {
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : "";
        if (tail.matches("\\d{3}")) {
            return Integer.parseInt(tail);
        }
        return 200;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String id() {
        return id;
    }

    public int activeCount() {
        return active.get();
    }

    public int peakCount() {
        return peak.get();
    }

    /** 打开挂起开关：此后进入 /hold/ 的请求会在服务端阻塞，直到 releaseAllHolds。 */
    public void armHolds() {
        this.releaseGate = new CountDownLatch(1);
    }

    public void releaseAllHolds() {
        releaseGate.countDown();
    }

    @Override
    public void close() {
        releaseAllHolds();
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
