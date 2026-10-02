package com.github.feng111627.dynamicapigatewayjava.ratelimit;

public record RateLimitDecision(
        boolean allowed,
        RateLimitScope scope,
        String routeId,
        String callerId,
        int limit,
        int windowSeconds,
        int used,
        long windowStartMs
) {
    public static RateLimitDecision pass(RateLimitScope scope, String routeId, String callerId,
                                         int limit, int windowSeconds, int used, long windowStartMs) {
        return new RateLimitDecision(true, scope, routeId, callerId, limit, windowSeconds, used, windowStartMs);
    }

    public static RateLimitDecision rejected(RateLimitScope scope, String routeId, String callerId,
                                             int limit, int windowSeconds, int used, long windowStartMs) {
        return new RateLimitDecision(false, scope, routeId, callerId, limit, windowSeconds, used, windowStartMs);
    }
}
