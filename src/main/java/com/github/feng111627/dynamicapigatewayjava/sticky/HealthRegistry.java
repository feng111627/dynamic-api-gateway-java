package com.github.feng111627.dynamicapigatewayjava.sticky;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 实例健康注册表。默认健康；转发失败时标记不健康，恢复探测成功后标记健康。
 *
 * 状态变化（且确实发生翻转）时通知监听器，供：
 *  - 均衡器在实例恢复时进入预热并重置轮询；
 *  - 外部通过管理端观察健康状态变化。
 * 只有发生真实翻转（healthy->unhealthy 或反向）才通知，重复标记不产生事件，
 * 避免把本来健康的实例误判或引发不必要的流量抖动。
 */
public class HealthRegistry {

    public enum State {
        HEALTHY,
        UNHEALTHY
    }

    public record StateChange(String backendId, State state, long atMillis) {
    }

    private final Set<String> unhealthy = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Consumer<StateChange>> listeners = new CopyOnWriteArrayList<>();
    private final LongSupplier clock;

    public HealthRegistry() {
        this(System::currentTimeMillis);
    }

    public HealthRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public void addListener(Consumer<StateChange> listener) {
        listeners.add(listener);
    }

    /** 标记健康：仅当此前为不健康时才触发“恢复”事件；一直健康的实例不会产生事件。 */
    public void markHealthy(String backendId) {
        if (unhealthy.remove(backendId)) {
            fire(backendId, State.HEALTHY);
        }
    }

    /** 标记不健康：仅当此前为健康时才触发“故障”事件。 */
    public void markUnhealthy(String backendId) {
        if (unhealthy.add(backendId)) {
            fire(backendId, State.UNHEALTHY);
        }
    }

    public boolean isHealthy(String backendId) {
        return !unhealthy.contains(backendId);
    }

    public State stateOf(String backendId) {
        return isHealthy(backendId) ? State.HEALTHY : State.UNHEALTHY;
    }

    /** 当前不健康实例 id 快照（外部可观察）。 */
    public Set<String> unhealthySnapshot() {
        return Set.copyOf(unhealthy);
    }

    /** 对给定实例集合给出 id -> 状态 的快照（外部可观察）。 */
    public Map<String, State> snapshot(Iterable<String> backendIds) {
        Map<String, State> out = new LinkedHashMap<>();
        for (String id : backendIds) {
            out.put(id, stateOf(id));
        }
        return Map.copyOf(out);
    }

    private void fire(String backendId, State state) {
        StateChange change = new StateChange(backendId, state, clock.getAsLong());
        for (Consumer<StateChange> l : listeners) {
            l.accept(change);
        }
    }
}
