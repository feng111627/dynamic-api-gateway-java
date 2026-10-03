package com.github.feng111627.dynamicapigatewayjava.balance;

import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 新会话选实例的负载均衡器：
 *  - 平滑加权轮询（smooth weighted round-robin，Nginx 风格）：按可配置权重分散新会话，
 *    长期分布与权重成正比，短期分布平滑，不会连续压同一实例；
 *  - 恢复预热闸门：实例刚恢复的一段时间内只接 1/recoveryShareDivisor 的新会话，
 *    避免恢复瞬间被一次性砸垮；预热结束后恢复全权重；
 *  - 健康拓扑翻转时重置轮询状态，保证选择不偏斜。
 *
 * 调用方传入的候选实例默认都是“健康”的。
 */
public class LoadBalancer {

    /** 每个路由的轮询状态。 */
    private static final class RouteState {
        final Map<String, AtomicLong> currentWeight = new HashMap<>();
        final Map<String, Integer> configuredWeight = new HashMap<>();
    }

    /** 预热信息：预热结束时刻（epoch millis）。 */
    private record Warming(long warmUntilMillis) {
        boolean isWarming(long nowMillis) {
            return nowMillis < warmUntilMillis;
        }
    }

    private final LoadBalanceStrategy strategy;
    private final int recoveryShareDivisor;
    private final ConcurrentHashMap<String, RouteState> routeStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Warming> warming = new ConcurrentHashMap<>();
    /** 每条路由上“新会话选择”的计数，用于预热闸门：每 N 次才放 1 次给预热实例。 */
    private final ConcurrentHashMap<String, AtomicLong> newSessionCounters = new ConcurrentHashMap<>();

    public LoadBalancer(LoadBalanceStrategy strategy, int recoveryShareDivisor) {
        this.strategy = strategy;
        this.recoveryShareDivisor = Math.max(1, recoveryShareDivisor);
    }

    /**
     * 在候选健康实例中选一个。预热中的实例默认不参与本轮选择，
     * 仅当计数值命中闸门（1/N）或没有任何非预热候选时才允许承接，实现渐进回流量。
     */
    public Optional<BackendInstance> choose(Route route, List<BackendInstance> candidates, long nowMillis) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<BackendInstance> normal = candidates.stream()
                .filter(b -> !isWarming(b.id(), nowMillis))
                .toList();
        List<BackendInstance> warmingOnes = candidates.stream()
                .filter(b -> isWarming(b.id(), nowMillis))
                .toList();

        long counter = newSessionCounters
                .computeIfAbsent(route.id(), k -> new AtomicLong())
                .getAndIncrement();

        if (!normal.isEmpty()) {
            // 每 N 次新会话，才有一次把预热实例纳入选择
            boolean gateOpenForWarming = !warmingOnes.isEmpty()
                    && Math.floorMod(counter, (long) recoveryShareDivisor) == 0;
            List<BackendInstance> pool = gateOpenForWarming ? candidates : normal;
            return pickWeighted(route, pool, nowMillis);
        }
        // 只剩预热实例可用（例如冷启动或全部刚恢复）：不能让流量没处去，直接在其中选
        return pickWeighted(route, warmingOnes, nowMillis);
    }

    /** 平滑加权轮询选择，预热实例在预热期内权重按 1 处理。 */
    private Optional<BackendInstance> pickWeighted(Route route, List<BackendInstance> pool, long nowMillis) {
        RouteState state = routeStates.computeIfAbsent(route.id(), k -> new RouteState());
        synchronized (state) {
            // 拓扑变化（新增/删除实例）时把状态补齐
            for (BackendInstance b : pool) {
                int effective = effectiveWeight(b, nowMillis);
                Integer old = state.configuredWeight.get(b.id());
                if (old == null || old != effective) {
                    state.currentWeight.put(b.id(), new AtomicLong(0));
                    state.configuredWeight.put(b.id(), effective);
                }
            }
            BackendInstance best = null;
            long bestCurrent = Long.MIN_VALUE;
            int totalWeight = 0;
            for (BackendInstance b : pool) {
                int w = state.configuredWeight.get(b.id());
                totalWeight += w;
                AtomicLong cur = state.currentWeight.get(b.id());
                long next = cur.addAndGet(w);
                if (next > bestCurrent) {
                    bestCurrent = next;
                    best = b;
                }
            }
            if (best != null) {
                state.currentWeight.get(best.id()).addAndGet(-totalWeight);
            }
            return Optional.ofNullable(best);
        }
    }

    private int effectiveWeight(BackendInstance b, long nowMillis) {
        if (isWarming(b.id(), nowMillis)) {
            return 1; // 预热期固定为最小权重
        }
        return Math.max(1, b.weight());
    }

    public boolean isWarming(String backendId, long nowMillis) {
        Warming w = warming.get(backendId);
        return w != null && w.isWarming(nowMillis);
    }

    /** 实例被重新标记健康（恢复）时调用：进入预热期。 */
    public void notifyRecovered(String backendId, long nowMillis, long warmupMillis) {
        if (warmupMillis > 0) {
            warming.put(backendId, new Warming(nowMillis + warmupMillis));
        }
    }

    /** 预热结束：移除预热标记，后续按配置权重全量参与。 */
    public void notifyWarmupDone(String backendId) {
        warming.remove(backendId);
    }

    /** 健康拓扑翻转（故障/恢复）时重置该路由的轮询状态，避免选择偏斜。 */
    public void resetState(String routeId) {
        routeStates.remove(routeId);
    }

    public LoadBalanceStrategy strategy() {
        return strategy;
    }
}
