package com.github.feng111627.dynamicapigatewayjava.gateway;

import java.util.List;
import java.util.Map;

/**
 * 网关对调用方的统一响应，错误类型可区分。
 * 成功时保留后端真实的状态码、响应头、响应体，由入口层原样回传。
 */
public record GatewayResponse(int status, Map<String, List<String>> headers, String body,
                              String backendId, ErrorKind errorKind, String errorDetail) {

    public enum ErrorKind {
        NONE,
        ROUTE_NOT_FOUND,
        RATE_LIMITED,
        NO_HEALTHY_BACKEND,
        BACKEND_UNREACHABLE,
        /** 健康实例存在，但都已达到并发上限：排队等待后仍拿不到容量。与其他 503 明确区分。 */
        BACKEND_OVERLOADED
    }

    /** 透传成功：状态码/响应头/响应体全部来自后端。 */
    public static GatewayResponse ok(int status, Map<String, List<String>> headers,
                                     String body, String backendId) {
        return new GatewayResponse(status, headers == null ? Map.of() : Map.copyOf(headers),
                body, backendId, ErrorKind.NONE, null);
    }

    /** 兼容旧用例：无响应头的成功结果。 */
    public static GatewayResponse ok(int status, String body, String backendId) {
        return ok(status, Map.of(), body, backendId);
    }

    public static GatewayResponse error(int status, ErrorKind kind, String detail) {
        return new GatewayResponse(status, Map.of(), null, null, kind, detail);
    }
}
