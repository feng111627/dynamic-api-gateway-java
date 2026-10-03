package com.github.feng111627.dynamicapigatewayjava.gateway;

import java.util.List;
import java.util.Map;

/** 发给后端的转发请求（骨架）：方法、头、查询、请求体全部透传。 */
public record ForwardRequest(String path, String method,
                             Map<String, List<String>> headers,
                             String query, String body) {

    public static ForwardRequest stub() {
        return new ForwardRequest("/", "GET", Map.of(), null, null);
    }
}
