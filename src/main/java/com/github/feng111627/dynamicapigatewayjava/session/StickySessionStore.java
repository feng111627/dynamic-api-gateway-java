package com.github.feng111627.dynamicapigatewayjava.session;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores current sticky bindings keyed by (routeId, callerId).
 *
 * Lookups are valid for {@code expiresAtMs}; an expired entry behaves as absent so the
 * dispatcher re-binds (which is when a recovered original instance can be picked again).
 * All mutation is atomic per key, so concurrent requests from one caller never observe a
 * torn entry.
 */
public class StickySessionStore {

    private final ConcurrentHashMap<String, StickyEntry> entries = new ConcurrentHashMap<>();

    private static String key(String routeId, String callerId) {
        return routeId + "|" + callerId;
    }

    public StickyEntry get(String routeId, String callerId, long nowMs) {
        StickyEntry entry = entries.get(key(routeId, callerId));
        if (entry == null || nowMs >= entry.expiresAtMs()) {
            return null;
        }
        return entry;
    }

    public StickyEntry save(StickyEntry entry) {
        entries.put(key(entry.routeId(), entry.callerId()), entry);
        return entry;
    }

    /** Re-bind while preserving the original-instance history used for controlled reattach. */
    public StickyEntry rebind(String routeId, String callerId, String newInstanceId,
                              long boundAtMs, long ttlMs) {
        String mapKey = key(routeId, callerId);
        return entries.compute(mapKey, (k, old) -> {
            String originalId = old == null ? newInstanceId : old.originalInstanceId();
            long originalFailedAt = old == null ? 0L : old.originalFailedAtMs();
            int generation = old == null ? 0 : old.reattachGeneration();
            return new StickyEntry(routeId, callerId, originalId, newInstanceId,
                    boundAtMs, boundAtMs + ttlMs, originalFailedAt, generation);
        });
    }

    /** Move the current target back to the (recovered) original instance. */
    public StickyEntry reattachToOriginal(StickyEntry entry, long nowMs, long ttlMs) {
        StickyEntry updated = new StickyEntry(entry.routeId(), entry.callerId(),
                entry.originalInstanceId(), entry.originalInstanceId(),
                nowMs, nowMs + ttlMs, 0L, entry.reattachGeneration() + 1);
        entries.put(key(entry.routeId(), entry.callerId()), updated);
        return updated;
    }

    /** Record when the originally bound instance was first seen failing (stamped once per failure episode). */
    public StickyEntry markOriginalFailed(StickyEntry entry, long nowMs) {
        if (entry.originalFailedAtMs() != 0L) {
            return entry;
        }
        StickyEntry updated = new StickyEntry(entry.routeId(), entry.callerId(),
                entry.originalInstanceId(), entry.currentInstanceId(),
                entry.boundAtMs(), entry.expiresAtMs(), nowMs, entry.reattachGeneration());
        entries.put(key(entry.routeId(), entry.callerId()), updated);
        return updated;
    }

    /** Slide the TTL window of an active binding after a successful request. */
    public StickyEntry touch(StickyEntry entry, long nowMs, long ttlMs) {
        StickyEntry updated = new StickyEntry(entry.routeId(), entry.callerId(),
                entry.originalInstanceId(), entry.currentInstanceId(),
                entry.boundAtMs(), nowMs + ttlMs,
                entry.originalFailedAtMs(), entry.reattachGeneration());
        entries.put(key(entry.routeId(), entry.callerId()), updated);
        return updated;
    }

    public void purgeExpired(long nowMs) {
        entries.entrySet().removeIf(e -> nowMs >= e.getValue().expiresAtMs());
    }

    public int size() {
        return entries.size();
    }
}
