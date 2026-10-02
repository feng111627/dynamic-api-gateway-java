package com.github.feng111627.dynamicapigatewayjava.demo;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 本地模拟后端：基于 JDK HttpServer 的 echo 服务。
 * 响应里带上自己的实例 id，便于验证请求落到了哪个实例。
 * port 传 0 时随机分配端口。
 */
public class FakeBackend implements AutoCloseable {

    private final String id;
    private final HttpServer server;

    public FakeBackend(String id, int port) {
        this.id = id;
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start fake backend " + id, e);
        }
        server.createContext("/", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String resp = "backend=" + id + " path=" + exchange.getRequestURI().getPath()
                    + " body=" + reqBody;
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String id() {
        return id;
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
