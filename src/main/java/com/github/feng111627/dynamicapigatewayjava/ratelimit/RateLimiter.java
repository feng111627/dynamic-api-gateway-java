package com.github.feng111627.dynamicapigatewayjava.ratelimit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter, dimensioned independently per route and per caller.
 *
 * Concurrency guarantees:
 *  - The decision (read current window, roll it over if expired, increment the counter)
 *    runs inside {@link ConcurrentHashMap#compute}, which is atomic per key. Concurrent
 *    threads therefore serialize on that key: quota can never be over-issued, and at a
 *    window boundary exactly one thread creates the new window, so the first window's
 *    counter is neither double-counted nor lost.
 *  - Route and caller counters live under different keys, so route-level traffic only
 *    contends with other route-level traffic for the same route.
 */
public class RateLimiter {

    private record Window(long startMs, int windowSeconds, int limit, java.util.concurrent.atomic.AtomicInteger used) {
        boolean contains(long nowMs) {
            return nowMs >= startMs && nowMs < startMs + windowSeconds * 1000L;
        }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitDecision checkRoute(String routeId, int limit, int windowSeconds, long nowMs) {
        return check(RateLimitScope.ROUTE, key(RateLimitScope.ROUTE, routeId, null),
                routeId, null, limit, windowSeconds, nowMs);
    }

    public RateLimitDecision checkCaller(String routeId, String callerId, int limit, int windowSeconds, long nowMs) {
        if (callerId == null || callerId.isBlank()) {
            // Anonymous callers are not subject to per-caller quota; dispatcher checks route quota only.
            return null;
        }
        return check(RateLimitScope.CALLER, key(RateLimitScope.CALLER, routeId, callerId),
                routeId, callerId, limit, windowSeconds, nowMs);
    }

    private RateLimitDecision check(RateLimitScope scope, String mapKey, String routeId, String callerId,
                                    int limit, int windowSeconds, long nowMs) {
        final long now = nowMs;
        Window resolved = windows.compute(mapKey, (k, current) -> {
            if (current == null || !current.contains(now)) {
                // First ever request, or the previous window has fully elapsed: open a fresh one.
                return new Window(windowStart(now, windowSeconds), windowSeconds, limit,
                        new java.util.concurrent.atomic.AtomicInteger(0));
            }
            return current;
        });
        int used = resolved.used().incrementAndGet();
        boolean allowed = used <= limit;
        RateLimitDecision decision = allowed
                ? RateLimitDecision.pass(scope, routeId, callerId, limit, windowSeconds, used, resolved.startMs())
                : RateLimitDecision.rejected(scope, routeId, callerId, limit, windowSeconds, used, resolved.startMs());
        return decision;
    }

    private long windowStart(long nowMs, int windowSeconds) {
        long windowMillis = windowSeconds * 1000L;
        return Math.floorDiv(nowMs, windowMillis) * windowMillis;
    }

    private String key(RateLimitScope scope, String routeId, String callerId) {
        return scope == RateLimitScope.ROUTE
                ? "R:" + routeId
                : "C:" + routeId + ":" + callerId;
    }

    /** Drop every counter. Invoked atomically together with a successful rule swap. */
    public void reset() {
        windows.clear();
    }

    /** Remove windows already past their end time, so the map does not grow without bound. */
    public void purgeExpiredWindows(long nowMs) {
        List<String> stale = new ArrayList<>();
        windows.forEach((k, w) -> {
            if (nowMs >= w.startMs() + w.windowSeconds() * 1000L) {
                stale.add(k);
            }
        });
        stale.forEach(k -> windows.computeIfPresent(k, (key, w) ->
                nowMs >= w.startMs() + w.windowSeconds() * 1000L ? null : w));
    }
}
