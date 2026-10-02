package com.github.feng111627.dynamicapigatewayjava.forward;

import java.util.List;
import java.util.Map;

public record ForwardResponse(
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body,
        boolean transportError,
        String errorMessage
) {
    public static ForwardResponse transportError(String message) {
        return new ForwardResponse(502, Map.of(), new byte[0], true, message);
    }
}
