package com.github.feng111627.dynamicapigatewayjava.sticky;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 粘性会话存储：调用方 -> 后端实例的绑定，带 TTL。
 * 失效条件：超过 sessionTtlMillis 未续期，或显式 evict（如实例不健康时）。
 */
public class StickySessionStore {

    private final long sessionTtlMillis;
    private final ConcurrentHashMap<String, StickySession> sessions = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public StickySessionStore(long sessionTtlMillis) {
        this(sessionTtlMillis, System::currentTimeMillis);
    }

    public StickySessionStore(long sessionTtlMillis, LongSupplier clock) {
        this.sessionTtlMillis = sessionTtlMillis;
        this.clock = clock;
    }

    /** 查找未过期的绑定；过期绑定视为不存在并顺手清理。 */
    public Optional<StickySession> find(String callerId) {
        StickySession s = sessions.get(callerId);
        if (s == null) {
            return Optional.empty();
        }
        if (s.expired(clock.getAsLong())) {
            sessions.remove(callerId, s);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    /** 建立/刷新绑定，TTL 从绑定时刻重新计算。 */
    public void bind(String callerId, String backendId) {
        sessions.put(callerId, new StickySession(callerId, backendId,
                clock.getAsLong() + sessionTtlMillis));
    }

    /** 仅当当前绑定仍指向 expectedBackendId 时才解除，避免误删已切换的新绑定。 */
    public void evictIfBoundTo(String callerId, String expectedBackendId) {
        sessions.computeIfPresent(callerId, (k, s) ->
                s.backendId().equals(expectedBackendId) ? null : s);
    }

    public void evict(String callerId) {
        sessions.remove(callerId);
    }

    public long ttlMillis() {
        return sessionTtlMillis;
    }
}
