package com.github.feng111627.dynamicapigatewayjava.sticky;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * 实例健康注册表。默认健康；转发失败时标记不健康；
 * 恢复探测首次成功后进入 RECOVERING（承接少量流量、缓升），
 * 暖机期满后回到 HEALTHY。
 *
 * 状态变化通过监听器对外发布，外部可观察每一次迁移。
 */
public class HealthRegistry {

    public enum State {
        HEALTHY,
        /** 转发失败被切走，不接受新流量，等待恢复探测。 */
        UNHEALTHY,
        /** 探测已成功、处于暖机期：可承接少量新流量，避免恢复瞬间被打垮。 */
        RECOVERING
    }

    /** 一次可观察的健康状态迁移。 */
    public record HealthEvent(String backendId, State from, State to, long timestampMillis) {
    }

    public interface HealthListener {
        void onHealthChange(HealthEvent event);
    }

    private record Entry(State state, long sinceMillis) {
    }

    private final ConcurrentHashMap<String, Entry> states = new ConcurrentHashMap<>();
    private final List<HealthListener> listeners = new CopyOnWriteArrayList<>();
    private final LongSupplier clock;

    public HealthRegistry() {
        this(System::currentTimeMillis);
    }

    public HealthRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public void addListener(HealthListener listener) {
        listeners.add(listener);
    }

    public void markHealthy(String backendId) {
        transition(backendId, State.HEALTHY);
    }

    public void markUnhealthy(String backendId) {
        transition(backendId, State.UNHEALTHY);
    }

    /** 恢复探测首次成功：进入暖机期。 */
    public void markRecovering(String backendId) {
        transition(backendId, State.RECOVERING);
    }

    private void transition(String backendId, State to) {
        long now = clock.getAsLong();
        State[] from = new State[1];
        states.compute(backendId, (id, old) -> {
            from[0] = old == null ? State.HEALTHY : old.state();
            if (from[0] == to) {
                return old == null ? null : old; // 状态没变，不动
            }
            return new Entry(to, now);
        });
        if (from[0] != null && from[0] != to) {
            HealthEvent event = new HealthEvent(backendId, from[0], to, now);
            listeners.forEach(l -> l.onHealthChange(event));
        }
    }

    public State stateOf(String backendId) {
        Entry e = states.get(backendId);
        return e == null ? State.HEALTHY : e.state();
    }

    /** 进入当前状态的时刻（健康实例无记录时返回 -1）。 */
    public long stateSince(String backendId) {
        Entry e = states.get(backendId);
        return e == null ? -1 : e.sinceMillis();
    }

    /** 可承接流量：HEALTHY 或 RECOVERING（暖机期少量）。 */
    public boolean isHealthy(String backendId) {
        return stateOf(backendId) != State.UNHEALTHY;
    }

    /** 供外部观察的只读快照：backendId -> 状态名。 */
    public Map<String, String> snapshot() {
        Map<String, String> out = new ConcurrentHashMap<>();
        states.forEach((id, e) -> out.put(id, e.state().name()));
        return out;
    }
}
