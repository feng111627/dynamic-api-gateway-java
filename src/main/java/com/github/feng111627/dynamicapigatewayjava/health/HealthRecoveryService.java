package com.github.feng111627.dynamicapigatewayjava.health;

import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalancer;
import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实例自动恢复服务。
 *  - 周期性对“当前不健康”的实例做探活；
 *  - 探活成功（TCP/HTTP 能连通）即自动标记健康（通过 HealthRegistry 的翻转事件，
 *    均衡器进入预热并重置轮询），无需人工改配置或重启；
 *  - 预热到期后通知均衡器恢复全权重；
 *  - 正在处理的请求不会被打断：健康状态只影响后续新选择，转发中的连接不受影响；
 *  - 探测只做“救活”，从不把健康实例标记为不健康，因此不可能误判健康实例。
 */
public class HealthRecoveryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthRecoveryService.class);

    private final HealthRegistry registry;
    private final HealthProbe probe;
    private final RouteRegistry routeRegistry;
    private final LoadBalancer loadBalancer;
    private final long probeIntervalMillis;
    private final long warmupMillis;
    private final LongSupplier clock;

    /** backendId -> 预热结束时刻。 */
    private final Map<String, Long> warmUntil = new LinkedHashMap<>();

    private ScheduledExecutorService executor;

    public HealthRecoveryService(HealthRegistry registry, HealthProbe probe, RouteRegistry routeRegistry,
                                 LoadBalancer loadBalancer,
                                 long probeIntervalMillis, long warmupMillis, LongSupplier clock) {
        this.registry = registry;
        this.probe = probe;
        this.routeRegistry = routeRegistry;
        this.loadBalancer = loadBalancer;
        this.probeIntervalMillis = probeIntervalMillis;
        this.warmupMillis = warmupMillis;
        this.clock = clock;
    }

    /**
     * 执行一轮扫描：枚举所有路由下当前不健康的实例并探活。
     * 返回本轮恢复的实例 id 列表。探活失败维持不健康，等待下一轮。
     */
    public synchronized List<String> scanOnce() {
        List<String> recovered = new ArrayList<>();
        for (BackendInstance backend : unhealthyBackends()) {
            boolean alive;
            try {
                alive = probe.isAlive(backend);
            } catch (Exception e) {
                alive = false;
            }
            if (alive) {
                long now = clock.getAsLong();
                registry.markHealthy(backend.id());
                loadBalancer.notifyRecovered(backend.id(), now, warmupMillis);
                if (warmupMillis > 0) {
                    warmUntil.put(backend.id(), now + warmupMillis);
                }
                recovered.add(backend.id());
                log.info("backend {} recovered by probe, warming up for {} ms",
                        backend.id(), warmupMillis);
            }
        }
        expireWarmups();
        return recovered;
    }

    /** 预热到期的实例恢复全权重。 */
    synchronized void expireWarmups() {
        long now = clock.getAsLong();
        var it = warmUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now >= e.getValue()) {
                loadBalancer.notifyWarmupDone(e.getKey());
                it.remove();
                log.info("backend {} warmup done, full weight restored", e.getKey());
            }
        }
    }

    /** 从最新路由表快照中枚举所有不健康实例（去重）。 */
    private List<BackendInstance> unhealthyBackends() {
        Map<String, BackendInstance> all = new LinkedHashMap<>();
        for (Route route : routeRegistry.snapshot().routes()) {
            for (BackendInstance b : route.backends()) {
                all.putIfAbsent(b.id(), b);
            }
        }
        List<BackendInstance> down = new ArrayList<>();
        all.forEach((id, b) -> {
            if (!registry.isHealthy(id)) {
                down.add(b);
            }
        });
        return down;
    }

    /** 启动后台周期任务（默认守护线程，不阻止 JVM 退出）。 */
    public void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-recovery");
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(50, probeIntervalMillis);
        executor.scheduleAtFixedRate(() -> {
            try {
                scanOnce();
            } catch (Throwable t) {
                log.warn("health scan error: {}", t.toString());
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
