package com.github.feng111627.dynamicapigatewayjava.gateway;

/** 网关对调用方的统一响应，错误类型可区分。 */
public record GatewayResponse(int status, String body, String backendId,
                              ErrorKind errorKind, String errorDetail) {

    public enum ErrorKind {
        NONE,
        ROUTE_NOT_FOUND,
        RATE_LIMITED,
        NO_HEALTHY_BACKEND,
        BACKEND_UNREACHABLE
    }

    public static GatewayResponse ok(int status, String body, String backendId) {
        return new GatewayResponse(status, body, backendId, ErrorKind.NONE, null);
    }

    public static GatewayResponse error(int status, ErrorKind kind, String detail) {
        return new GatewayResponse(status, null, null, kind, detail);
    }
}
