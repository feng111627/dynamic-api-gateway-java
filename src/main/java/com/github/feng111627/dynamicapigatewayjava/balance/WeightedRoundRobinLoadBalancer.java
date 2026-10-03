package com.github.feng111627.dynamicapigatewayjava.balance;

import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

/**
 * 加权轮询：把候选实例按有效权重展开后轮转选取。
 * 有效权重由调用方注入（例如恢复期的实例权重被压到 1，实现流量缓升），
 * 策略本身只负责"按权重分散，不固定压在同一实例上"。
 */
public class WeightedRoundRobinLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ToIntFunction<BackendInstance> effectiveWeight;

    /** 默认使用实例自身配置的权重。 */
    public WeightedRoundRobinLoadBalancer() {
        this(BackendInstance::weight);
    }

    public WeightedRoundRobinLoadBalancer(ToIntFunction<BackendInstance> effectiveWeight) {
        this.effectiveWeight = effectiveWeight;
    }

    @Override
    public Optional<BackendInstance> select(String routeId, List<BackendInstance> eligible) {
        if (eligible == null || eligible.isEmpty()) {
            return Optional.empty();
        }
        List<BackendInstance> expanded = new ArrayList<>();
        for (BackendInstance b : eligible) {
            int w = Math.max(1, effectiveWeight.applyAsInt(b));
            for (int i = 0; i < w; i++) {
                expanded.add(b);
            }
        }
        long idx = counters.computeIfAbsent(routeId, k -> new AtomicLong()).getAndIncrement();
        return Optional.of(expanded.get((int) Long.remainderUnsigned(idx, expanded.size())));
    }
}
