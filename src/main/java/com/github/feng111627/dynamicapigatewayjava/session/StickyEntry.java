package com.github.feng111627.dynamicapigatewayjava.session;

public record StickyEntry(
        String routeId,
        String callerId,
        String originalInstanceId,
        String currentInstanceId,
        long boundAtMs,
        long expiresAtMs,
        long originalFailedAtMs,
        int reattachGeneration
) {
}
