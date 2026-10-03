package com.github.feng111627.dynamicapigatewayjava.gateway;

import java.util.List;
import java.util.Map;

/** 一次转发的最终结果：每个请求都有明确结果，不会无声丢失。 */
public record ForwardResult(boolean success, int statusCode, String body,
                            Map<String, List<String>> headers,
                            String backendId, String error) {

    public ForwardResult {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static ForwardResult ok(int status, String body, Map<String, List<String>> headers,
                                   String backendId) {
        return new ForwardResult(true, status, body, headers, backendId, null);
    }

    public static ForwardResult failure(String backendId, String error) {
        return new ForwardResult(false, 0, null, Map.of(), backendId, error);
    }
}
