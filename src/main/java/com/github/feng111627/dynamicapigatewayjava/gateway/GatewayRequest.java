package com.github.feng111627.dynamicapigatewayjava.gateway;

import java.util.Map;

/**
 * 进入网关的一次请求的完整语义：方法、路径、查询串、请求头、请求体、调用方标识。
 * 网关转发时原样透传，不把语义不同的请求归一成同一种。
 */
public record GatewayRequest(String method, String path, String query,
                             Map<String, String> headers, String callerId, String body) {

    public GatewayRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 兼容旧入口：等价于一个不带查询串和自定义头的 POST。 */
    public static GatewayRequest post(String path, String callerId, String body) {
        return new GatewayRequest("POST", path, null, Map.of(), callerId, body);
    }
}
