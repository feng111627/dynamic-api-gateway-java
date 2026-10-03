package com.github.feng111627.dynamicapigatewayjava.balance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单实例并发上限：按实例 id 计数在途请求。
 * tryAcquire 失败表示该实例已满——调用方必须给出明确结果（重选实例或拒绝），
 * 既不超发也不静默丢弃。release 与 tryAcquire 必须成对（成功后）。
 */
public class ConcurrencyLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /** maxConcurrency <= 0 表示不限。 */
    public boolean tryAcquire(String backendId, int maxConcurrency) {
        if (maxConcurrency <= 0) {
            return true;
        }
        AtomicInteger counter = inFlight.computeIfAbsent(backendId, k -> new AtomicInteger());
        while (true) {
            int cur = counter.get();
            if (cur >= maxConcurrency) {
                return false;
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    public void release(String backendId) {
        AtomicInteger counter = inFlight.get(backendId);
        if (counter != null) {
            counter.updateAndGet(c -> Math.max(0, c - 1));
        }
    }

    public int inFlight(String backendId) {
        AtomicInteger counter = inFlight.get(backendId);
        return counter == null ? 0 : counter.get();
    }
}
