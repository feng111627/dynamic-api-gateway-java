package com.github.feng111627.dynamicapigatewayjava.gateway;

import java.util.List;
import java.util.Map;

/**
 * 一次转发的最终结果：每个请求都有明确结果，不会无声丢失。
 * 成功时携带后端真实的状态码、响应头与响应体（语义原样透传，不做归一）。
 */
public record ForwardResult(boolean success, int statusCode,
                            Map<String, List<String>> headers, String body,
                            String backendId, String error) {

    public static ForwardResult ok(int status, Map<String, List<String>> headers,
                                   String body, String backendId) {
        return new ForwardResult(true, status,
                headers == null ? Map.of() : Map.copyOf(headers), body, backendId, null);
    }

    public static ForwardResult failure(String backendId, String error) {
        return new ForwardResult(false, 0, Map.of(), null, backendId, error);
    }
}
