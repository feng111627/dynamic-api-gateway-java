package com.github.feng111627.dynamicapigatewayjava.forward;

import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forwards one request to one backend instance using the JDK built-in HTTP client.
 *
 * Transport-level failures (connection refused, reset, timeout) never escape: they are
 * returned as {@link ForwardResponse#transportError} so the dispatcher can count the
 * failure against the instance and fail over exactly once — never double-sending to two
 * instances for a retryable result and never dropping the request without a result.
 */
public class BackendForwarder {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final HttpClient client;
    private final Duration requestTimeout;

    public BackendForwarder(int timeoutMs) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        this.requestTimeout = Duration.ofMillis(timeoutMs);
    }

    public ForwardResponse forward(BackendInstance instance, ForwardRequest request) {
        try {
            URI uri = buildUri(instance, request);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout);

            boolean hasBody = request.body() != null && request.body().length > 0;
            HttpRequest.BodyPublisher publisher = hasBody
                    ? HttpRequest.BodyPublishers.ofByteArray(request.body())
                    : HttpRequest.BodyPublishers.noBody();
            builder.method(request.method(), publisher);

            if (request.headers() != null) {
                for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                    if (HOP_BY_HOP.contains(header.getKey().toLowerCase())) {
                        continue;
                    }
                    for (String value : header.getValue()) {
                        builder.header(header.getKey(), value);
                    }
                }
            }

            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new ForwardResponse(response.statusCode(), filterResponseHeaders(response.headers().map()),
                    response.body() == null ? new byte[0] : response.body(), false, null);
        } catch (Exception ex) {
            return ForwardResponse.transportError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private Map<String, List<String>> filterResponseHeaders(Map<String, List<String>> source) {
        List<Map.Entry<String, List<String>>> kept = new ArrayList<>();
        source.forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                kept.add(Map.entry(name, values));
            }
        });
        java.util.LinkedHashMap<String, List<String>> copy = new java.util.LinkedHashMap<>();
        kept.forEach(e -> copy.put(e.getKey(), List.copyOf(e.getValue())));
        return Map.copyOf(copy);
    }

    private URI buildUri(BackendInstance instance, ForwardRequest request) {
        String base = instance.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = request.path() == null ? "/" : request.path();
        String url = base + path;
        if (request.query() != null && !request.query().isBlank()) {
            url += "?" + request.query();
        }
        return URI.create(url);
    }
}
