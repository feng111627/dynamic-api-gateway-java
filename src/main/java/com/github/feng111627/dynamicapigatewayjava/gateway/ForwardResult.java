package com.github.feng111627.dynamicapigatewayjava.gateway;

/** 一次转发的最终结果：每个请求都有明确结果，不会无声丢失。 */
public record ForwardResult(boolean success, int statusCode, String body,
                            String backendId, String error) {

    public static ForwardResult ok(int status, String body, String backendId) {
        return new ForwardResult(true, status, body, backendId, null);
    }

    public static ForwardResult failure(String backendId, String error) {
        return new ForwardResult(false, 0, null, backendId, error);
    }
}
