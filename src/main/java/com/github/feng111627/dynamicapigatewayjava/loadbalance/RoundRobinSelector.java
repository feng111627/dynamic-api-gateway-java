package com.github.feng111627.dynamicapigatewayjava.loadbalance;

import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe round-robin picker. Only healthy candidates are expected to be passed in;
 * when none are available it returns null so the dispatcher can return an explicit error
 * instead of silently dropping the request.
 */
public class RoundRobinSelector {

    private final AtomicLong counter = new AtomicLong(0);

    public BackendInstance select(List<BackendInstance> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int index = (int) Math.floorMod(counter.getAndIncrement(), candidates.size());
        return candidates.get(index);
    }
}
