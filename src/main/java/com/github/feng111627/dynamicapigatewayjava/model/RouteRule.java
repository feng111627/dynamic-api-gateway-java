package com.github.feng111627.dynamicapigatewayjava.model;

import java.util.List;

public record RouteRule(
        String id,
        String pathPrefix,
        List<BackendInstance> backends,
        boolean stickySession,
        RateLimitSpec rateLimit
) {
    public RouteRule {
        backends = backends == null ? List.of() : List.copyOf(backends);
    }

    public record RateLimitSpec(
            Integer routeLimit,
            Integer callerLimit,
            Integer windowSeconds
    ) {
    }
}
