package com.github.feng111627.dynamicapigatewayjava.gateway;

import java.util.List;
import java.util.Map;

/**
 * 网关收到的一次业务请求的完整描述（骨架）。
 * method/headers/query/body 原样承接，后续转发时原样带给后端。
 */
public record GatewayRequest(String path, String method,
                             Map<String, List<String>> headers,
                             String query, String body) {

    public static GatewayRequest stub() {
        return new GatewayRequest("/", "GET", Map.of(), null, null);
    }
}
