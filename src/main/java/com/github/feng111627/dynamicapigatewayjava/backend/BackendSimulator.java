package com.github.feng111627.dynamicapigatewayjava.backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local in-process backend simulator: starts a few plain JDK HTTP servers acting like
 * real upstream instances. Exists purely so the gateway can be exercised end to end on
 * one machine with no external dependencies.
 *
 * Per instance (instance id: backend-1, backend-2, ...; port: basePort + index):
 *   GET  /health    200 when up (so active probes recover it), 503 when forced down
 *   *    /*         echoes the instance id + a per-instance request count when up,
 *                   503 when forced down
 *   POST /control   body up|down — simulate failure / recovery without killing the socket
 *   GET  /metrics   request counters for assertion from tests or curl
 */
@Component
public class BackendSimulator {

    @Value("${gateway.backend-simulator.enabled:false}")
    private boolean enabled;

    @Value("${gateway.backend-simulator.base-port:18081}")
    private int basePort;

    @Value("${gateway.backend-simulator.instances:2}")
    private int instanceCount;

    private final Map<String, HttpServer> servers = new ConcurrentHashMap<>();
    private final Map<String, Integer> ports = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Boolean> forcedDown = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() throws IOException {
        if (!enabled) {
            return;
        }
        for (int i = 1; i <= instanceCount; i++) {
            String id = "backend-" + i;
            int port = basePort + i - 1;
            counters.put(id, new AtomicLong());
            forcedDown.put(id, false);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", exchange -> handle(id, exchange));
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "simulator-" + id);
                t.setDaemon(true);
                return t;
            }));
            server.start();
            servers.put(id, server);
            ports.put(id, port);
        }
    }

    private void handle(String id, HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && "/control".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                        .trim().toLowerCase();
                if ("down".equals(body) || "up".equals(body)) {
                    forcedDown.put(id, "down".equals(body));
                    writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"state\":\""
                            + ("down".equals(body) ? "down" : "up") + "\"}");
                } else {
                    writeJson(exchange, 400, "{\"error\":\"body must be up or down\"}");
                }
                return;
            }

            boolean down = forcedDown.getOrDefault(id, false);
            if ("/health".equals(path)) {
                if (down) {
                    writeJson(exchange, 503, "{\"id\":\"" + id + "\",\"status\":\"DOWN\"}");
                } else {
                    writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"status\":\"UP\"}");
                }
                return;
            }
            if ("/metrics".equals(path)) {
                writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"requests\":"
                        + counters.get(id).get() + ",\"forcedDown\":" + down + "}");
                return;
            }
            if (down) {
                writeJson(exchange, 503, "{\"id\":\"" + id + "\",\"error\":\"forced down\"}");
                return;
            }
            long n = counters.get(id).incrementAndGet();
            String payload = "{\"id\":\"" + id + "\",\"requests\":" + n
                    + ",\"path\":\"" + escape(path) + "\",\"method\":\"" + exchange.getRequestMethod() + "\"}";
            writeJson(exchange, 200, payload);
        } finally {
            exchange.close();
        }
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @PreDestroy
    public void stop() {
        servers.values().forEach(server -> server.stop(0));
        servers.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Integer> instancePorts() {
        return Map.copyOf(ports);
    }

    /** baseUrl of one simulator instance, e.g. http://127.0.0.1:18081 */
    public String baseUrlOf(String id) {
        Integer port = ports.get(id);
        return port == null ? null : "http://127.0.0.1:" + port;
    }

    public Map<String, String> baseUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        ports.forEach((id, port) -> urls.put(id, "http://127.0.0.1:" + port));
        return Map.copyOf(urls);
    }
}
