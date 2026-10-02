package com.github.feng111627.dynamicapigatewayjava.health;

import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks per-instance health from two signals:
 *  - passive: real forwarding outcomes (success resets failures; enough consecutive failures trips the instance)
 *  - active: a background prober calls the backend health endpoint on a fixed interval;
 *    enough consecutive healthy probes bring an instance back.
 *
 * State transitions carry a timestamp; sticky-session reattach uses it to wait out a
 * backoff after recovery so traffic does not flap back to a freshly recovered instance.
 *
 * {@link #syncInstances(List)} replaces the tracked instance set: instances removed from
 * routing config are dropped together with their state, in the same atomic commit as the
 * rule swap, so probes can never target a retired backend.
 */
public class HealthManager {

    private static final class InstanceState {
        volatile HealthStatus status = HealthStatus.UNKNOWN;
        volatile long statusChangedAtMs = 0;
        int consecutiveFailures = 0;
        int consecutiveProbeSuccesses = 0;
    }

    private final ConcurrentHashMap<String, InstanceState> states = new ConcurrentHashMap<>();
    private volatile Map<String, BackendInstance> instances = Map.of();

    private final int unhealthyThreshold;
    private final int healthyThreshold;
    private final long probeIntervalMs;

    private ScheduledExecutorService scheduler;

    public HealthManager(int unhealthyThreshold, int healthyThreshold, long probeIntervalMs) {
        this.unhealthyThreshold = Math.max(1, unhealthyThreshold);
        this.healthyThreshold = Math.max(1, healthyThreshold);
        this.probeIntervalMs = probeIntervalMs;
    }

    public HealthStatus statusOf(String instanceId) {
        InstanceState state = states.get(instanceId);
        return state == null ? HealthStatus.UNKNOWN : state.status;
    }

    public long statusChangedAtMs(String instanceId) {
        InstanceState state = states.get(instanceId);
        return state == null ? 0L : state.statusChangedAtMs;
    }

    /** UNKNOWN counts as eligible: we start optimistic and let real outcomes/probes correct us. */
    public boolean isHealthy(BackendInstance instance) {
        if (instance == null) {
            return false;
        }
        return statusOf(instance.id()) != HealthStatus.UNHEALTHY;
    }

    public void onForwardSuccess(BackendInstance instance) {
        if (instance == null) {
            return;
        }
        InstanceState state = states.computeIfAbsent(instance.id(), id -> new InstanceState());
        synchronized (state) {
            state.consecutiveFailures = 0;
            if (state.status != HealthStatus.HEALTHY) {
                state.status = HealthStatus.HEALTHY;
                state.statusChangedAtMs = System.currentTimeMillis();
                state.consecutiveProbeSuccesses = 0;
            }
        }
    }

    public void onForwardFailure(BackendInstance instance) {
        if (instance == null) {
            return;
        }
        InstanceState state = states.computeIfAbsent(instance.id(), id -> new InstanceState());
        synchronized (state) {
            state.consecutiveFailures++;
            if (state.consecutiveFailures >= unhealthyThreshold && state.status != HealthStatus.UNHEALTHY) {
                state.status = HealthStatus.UNHEALTHY;
                state.statusChangedAtMs = System.currentTimeMillis();
                state.consecutiveProbeSuccesses = 0;
            }
        }
    }

    /** Called by the active prober for one instance. */
    public void recordProbe(String instanceId, boolean healthy, long nowMs) {
        InstanceState state = states.get(instanceId);
        if (state == null) {
            return; // instance retired by a config swap
        }
        synchronized (state) {
            if (healthy) {
                state.consecutiveProbeSuccesses++;
                if (state.consecutiveProbeSuccesses >= healthyThreshold
                        && state.status != HealthStatus.HEALTHY) {
                    state.status = HealthStatus.HEALTHY;
                    state.statusChangedAtMs = nowMs;
                }
            } else {
                state.consecutiveProbeSuccesses = 0;
                if (state.status != HealthStatus.UNHEALTHY) {
                    state.status = HealthStatus.UNHEALTHY;
                    state.statusChangedAtMs = nowMs;
                }
            }
        }
    }

    public void syncInstances(List<BackendInstance> current) {
        Map<String, BackendInstance> snapshot = new ConcurrentHashMap<>();
        for (BackendInstance instance : current) {
            snapshot.put(instance.id(), instance);
            states.computeIfAbsent(instance.id(), id -> new InstanceState());
        }
        this.instances = Map.copyOf(snapshot);
        states.keySet().retainAll(snapshot.keySet());
    }

    public void start(BackendProber prober) {
        if (probeIntervalMs <= 0 || scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gateway-health-prober");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> runProbeRound(prober),
                probeIntervalMs, probeIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void runProbeRound(BackendProber prober) {
        long nowMs = System.currentTimeMillis();
        for (BackendInstance instance : instances.values()) {
            try {
                recordProbe(instance.id(), prober.probe(instance.baseUrl()), nowMs);
            } catch (RuntimeException ex) {
                recordProbe(instance.id(), false, nowMs);
            }
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
