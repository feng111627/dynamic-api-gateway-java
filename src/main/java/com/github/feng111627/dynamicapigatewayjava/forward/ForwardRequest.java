package com.github.feng111627.dynamicapigatewayjava.forward;

import java.util.List;
import java.util.Map;

public record ForwardRequest(
        String method,
        String path,
        String query,
        Map<String, List<String>> headers,
        byte[] body
) {
}
