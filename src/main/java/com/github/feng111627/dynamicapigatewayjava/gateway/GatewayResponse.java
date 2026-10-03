package com.github.feng111627.dynamicapigatewayjava.gateway;

import java.util.List;
import java.util.Map;

/** 网关对调用方的统一响应，错误类型可区分。 */
public record GatewayResponse(int status, String body, Map<String, List<String>> headers,
                              String backendId, ErrorKind errorKind, String errorDetail) {

    public enum ErrorKind {
        NONE,
        ROUTE_NOT_FOUND,
        RATE_LIMITED,
        NO_HEALTHY_BACKEND,
        BACKEND_UNREACHABLE,
        /** 健康实例都在，但并发上限已满——与限流、无可用后端明确区分。 */
        BACKEND_AT_CAPACITY
    }

    public static GatewayResponse ok(int status, String body, Map<String, List<String>> headers,
                                     String backendId) {
        return new GatewayResponse(status, body, headers, backendId, ErrorKind.NONE, null);
    }

    public static GatewayResponse error(int status, ErrorKind kind, String detail) {
        return new GatewayResponse(status, null, Map.of(), null, kind, detail);
    }
}
