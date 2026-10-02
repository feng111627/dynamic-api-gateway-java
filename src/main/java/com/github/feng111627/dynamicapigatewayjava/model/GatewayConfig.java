package com.github.feng111627.dynamicapigatewayjava.model;

import java.util.List;

public record GatewayConfig(long version, List<RouteRule> routes) {
    public GatewayConfig {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
