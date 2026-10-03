package com.github.feng111627.dynamicapigatewayjava.health;

import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实例恢复探测器：周期性地只探测当前不健康/暖机中的实例
 * （健康实例不被探测，不会被误判）。
 *
 * 恢复节奏：UNHEALTHY --探测成功--> RECOVERING（暖机期，承接少量流量）
 *          --暖机期满且持续探测成功--> HEALTHY（恢复全量权重）。
 * 暖机期探测失败会退回 UNHEALTHY。整个过程只影响后续选实例，
 * 不打断正在处理的请求。
 */
public class HealthChecker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final HealthRegistry registry;
    private final RouteRegistry routeRegistry;
    private final String probePath;
    private final long intervalMillis;
    private final long warmupMillis;
    private final LongSupplier clock;
    private final HttpClient client;
    private final Duration probeTimeout;
    private final ScheduledExecutorService exec;

    public HealthChecker(HealthRegistry registry, RouteRegistry routeRegistry,
                         String probePath, long intervalMillis, long warmupMillis) {
        this(registry, routeRegistry, probePath, intervalMillis, warmupMillis,
                System::currentTimeMillis);
    }

    public HealthChecker(HealthRegistry registry, RouteRegistry routeRegistry,
                         String probePath, long intervalMillis, long warmupMillis,
                         LongSupplier clock) {
        this.registry = registry;
        this.routeRegistry = routeRegistry;
        this.probePath = probePath;
        this.intervalMillis = intervalMillis;
        this.warmupMillis = warmupMillis;
        this.clock = clock;
        this.probeTimeout = Duration.ofMillis(Math.max(200, Math.min(intervalMillis, 2_000)));
        this.client = HttpClient.newBuilder().connectTimeout(probeTimeout).build();
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-checker");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动周期探测（运行期自动恢复，无需人工改配置或重启）。 */
    public void start() {
        exec.scheduleWithFixedDelay(this::probeOnceSafely, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /** 执行一轮探测（测试可直接调用以获得确定性）。 */
    public void probeOnce() {
        for (BackendInstance backend : allBackends()) {
            HealthRegistry.State state = registry.stateOf(backend.id());
            if (state == HealthRegistry.State.HEALTHY) {
                continue; // 健康实例不探测，避免误判
            }
            boolean up = probe(backend);
            if (state == HealthRegistry.State.UNHEALTHY && up) {
                log.info("backend={} probe ok -> RECOVERING (warmup {}ms)", backend.id(), warmupMillis);
                registry.markRecovering(backend.id());
            } else if (state == HealthRegistry.State.RECOVERING) {
                if (!up) {
                    log.warn("backend={} probe failed during warmup -> UNHEALTHY", backend.id());
                    registry.markUnhealthy(backend.id());
                } else if (clock.getAsLong() - registry.stateSince(backend.id()) >= warmupMillis) {
                    log.info("backend={} warmup done -> HEALTHY", backend.id());
                    registry.markHealthy(backend.id());
                }
            }
        }
    }

    private void probeOnceSafely() {
        try {
            probeOnce();
        } catch (RuntimeException e) {
            log.warn("health probe round failed: {}", e.toString());
        }
    }

    private boolean probe(BackendInstance backend) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(backend.baseUrl() + probePath))
                    .timeout(probeTimeout)
                    .GET()
                    .build();
            // 能拿到任何 HTTP 响应即视为实例已起来
            client.send(req, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Set<BackendInstance> allBackends() {
        Set<BackendInstance> all = new LinkedHashSet<>();
        routeRegistry.snapshot().routes().forEach(r -> all.addAll(r.backends()));
        return all;
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
