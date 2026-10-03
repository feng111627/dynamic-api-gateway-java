package com.github.feng111627.dynamicapigatewayjava.gateway;

import com.github.feng111627.dynamicapigatewayjava.balance.InstanceConcurrencyLimiter;
import com.github.feng111627.dynamicapigatewayjava.balance.InstanceConcurrencyLimiter.AcquireResult;
import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalanceStrategy;
import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalancer;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimitDecision;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.StickySession;
import com.github.feng111627.dynamicapigatewayjava.sticky.StickySessionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关编排：路由匹配 -> 限流 -> 粘性/均衡选实例 -> 容量许可 -> 转发，失败自动切换。
 *
 * 切换语义：每次转发尝试只发往一个实例；失败实例被标记不健康并解除粘性绑定后，
 * 才在剩余健康实例中选择下一个重试。任何时刻一个请求不会同时发往多个实例，
 * 且每次请求都以明确的 GatewayResponse 结束（成功或带分类的错误）。
 *
 * 容量语义：选中实例若已达并发上限，则先按 acquireTimeoutMillis 排队等待；
 * 等不到再尝试其他健康实例；所有健康实例都拿不到容量时返回 BACKEND_OVERLOADED，
 * 与限流(429)、无路由(404)、后端全挂(503 NO_HEALTHY_BACKEND)明确区分。
 */
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final RouteRegistry routeRegistry;
    private final RateLimiter rateLimiter;
    private final StickySessionStore sessionStore;
    private final HealthRegistry healthRegistry;
    private final Forwarder forwarder;
    private final LoadBalancer loadBalancer;
    private final InstanceConcurrencyLimiter concurrencyLimiter;
    private final long acquireTimeoutMillis;
    private final LongSupplier clock;

    /** 兼容旧用法：默认加权轮询、不做并发排队以外的额外限制、系统时钟。 */
    public GatewayService(RouteRegistry routeRegistry, RateLimiter rateLimiter,
                          StickySessionStore sessionStore, HealthRegistry healthRegistry,
                          Forwarder forwarder) {
        this(routeRegistry, rateLimiter, sessionStore, healthRegistry, forwarder,
                new LoadBalancer(LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN, 4),
                new InstanceConcurrencyLimiter(), 200, System::currentTimeMillis);
    }

    public GatewayService(RouteRegistry routeRegistry, RateLimiter rateLimiter,
                          StickySessionStore sessionStore, HealthRegistry healthRegistry,
                          Forwarder forwarder, LoadBalancer loadBalancer,
                          InstanceConcurrencyLimiter concurrencyLimiter,
                          long acquireTimeoutMillis, LongSupplier clock) {
        this.routeRegistry = routeRegistry;
        this.rateLimiter = rateLimiter;
        this.sessionStore = sessionStore;
        this.healthRegistry = healthRegistry;
        this.forwarder = forwarder;
        this.loadBalancer = loadBalancer;
        this.concurrencyLimiter = concurrencyLimiter;
        this.acquireTimeoutMillis = acquireTimeoutMillis;
        this.clock = clock;
        // 健康拓扑翻转（故障/恢复）即重置相关路由的轮询状态，保证恢复后选择不偏斜
        healthRegistry.addListener(change -> routeRegistry.snapshot().routes().forEach(route -> {
            boolean onRoute = route.backends().stream()
                    .anyMatch(b -> b.id().equals(change.backendId()));
            if (onRoute) {
                loadBalancer.resetState(route.id());
            }
        }));
    }

    /** 兼容旧调用：只有路径/调用方/请求体，按 POST 处理。 */
    public GatewayResponse handle(String path, String callerId, String body) {
        return handle(new GatewayRequest(path, "POST", Map.of(), null, body), callerId);
    }

    /**
     * 处理一次完整请求：方法/请求头/查询参数/请求体原样透传给后端。
     */
    public GatewayResponse handle(GatewayRequest request, String callerId) {
        Optional<Route> routeOpt = routeRegistry.match(request.path());
        if (routeOpt.isEmpty()) {
            return GatewayResponse.error(404, GatewayResponse.ErrorKind.ROUTE_NOT_FOUND,
                    "no route matches path: " + request.path());
        }
        Route route = routeOpt.get();

        RateLimitDecision decision = rateLimiter.check(route.id(), callerId);
        if (!decision.allowed()) {
            return GatewayResponse.error(429, GatewayResponse.ErrorKind.RATE_LIMITED,
                    "rejected by rule " + decision.ruleId()
                            + " (route=" + route.id() + ", caller=" + callerId + ")");
        }

        return forwardWithFailover(route, request, callerId);
    }

    private GatewayResponse forwardWithFailover(Route route, GatewayRequest request, String callerId) {
        List<String> tried = new ArrayList<>();
        List<String> capacityBlocked = new ArrayList<>();
        String lastError = null;
        String sessionKey = route.id() + "|" + callerId;
        ForwardRequest forwardRequest = new ForwardRequest(request.path(), request.method(),
                request.headers(), request.query(), request.body());

        while (true) {
            Optional<BackendInstance> pick = pickBackend(route, sessionKey, tried);
            if (pick.isEmpty()) {
                return classifyExhaustion(route, tried, capacityBlocked, lastError);
            }
            BackendInstance backend = pick.get();
            tried.add(backend.id());

            // 容量许可：满了先排队等待；超时则换别的健康实例，不超发也不静默丢弃
            AcquireResult permit = concurrencyLimiter.acquire(
                    backend.id(), backend.maxConcurrency(), acquireTimeoutMillis);
            if (permit.outcome() == InstanceConcurrencyLimiter.AcquireOutcome.INTERRUPTED) {
                return GatewayResponse.error(503, GatewayResponse.ErrorKind.BACKEND_OVERLOADED,
                        "interrupted while waiting capacity on route " + route.id());
            }
            if (!permit.acquired()) {
                capacityBlocked.add(backend.id());
                log.warn("route={} caller={} backend={} capacity full ({}), trying others",
                        route.id(), callerId, backend.id(), permit.outcome());
                continue;
            }
            try {
                ForwardResult result = forwarder.forward(backend, forwardRequest);
                if (result.success()) {
                    sessionStore.bind(sessionKey, backend.id());
                    log.info("route={} caller={} method={} -> backend={} status={} inflight={}",
                            route.id(), callerId, request.method(), backend.id(),
                            result.statusCode(), concurrencyLimiter.activeCount(backend.id()));
                    return GatewayResponse.ok(result.statusCode(), result.headers(),
                            result.body(), backend.id());
                }
                log.warn("route={} caller={} backend={} failed: {}", route.id(), callerId,
                        backend.id(), result.error());
                healthRegistry.markUnhealthy(backend.id());
                sessionStore.evictIfBoundTo(sessionKey, backend.id());
                lastError = result.error();
            } finally {
                // 请求处理完才归还名额，不会打断任何在途请求
                concurrencyLimiter.release(backend.id(), backend.maxConcurrency());
            }
        }
    }

    /**
     * 候选耗尽后的分类：
     *  - 一个健康实例都不剩 -> NO_HEALTHY_BACKEND；
     *  - 还有健康实例但全都因并发上限拿不到名额 -> BACKEND_OVERLOADED。
     */
    private GatewayResponse classifyExhaustion(Route route, List<String> tried,
                                               List<String> capacityBlocked, String lastError) {
        boolean anyHealthy = route.backends().stream()
                .anyMatch(b -> healthRegistry.isHealthy(b.id()));
        if (anyHealthy) {
            return GatewayResponse.error(503, GatewayResponse.ErrorKind.BACKEND_OVERLOADED,
                    "all healthy backends at capacity for route " + route.id()
                            + ", capacityBlocked=" + capacityBlocked);
        }
        return GatewayResponse.error(503, GatewayResponse.ErrorKind.NO_HEALTHY_BACKEND,
                "all backends failed for route " + route.id()
                        + ", tried=" + tried + ", lastError=" + lastError);
    }

    /**
     * 选实例：
     *  - 优先沿用未过期且健康、本轮未试过的粘性绑定；
     *  - 否则交给 LoadBalancer 在健康候选中按权重均衡选择（恢复实例受预热闸门保护）。
     */
    private Optional<BackendInstance> pickBackend(Route route, String sessionKey, List<String> excluded) {
        Optional<StickySession> sticky = sessionStore.find(sessionKey);
        if (sticky.isPresent()) {
            String boundId = sticky.get().backendId();
            Optional<BackendInstance> bound = route.backends().stream()
                    .filter(b -> b.id().equals(boundId))
                    .filter(b -> !excluded.contains(b.id()))
                    .findFirst();
            if (bound.isPresent() && healthRegistry.isHealthy(boundId)) {
                return bound;
            }
        }
        List<BackendInstance> healthy = route.backends().stream()
                .filter(b -> !excluded.contains(b.id()))
                .filter(b -> healthRegistry.isHealthy(b.id()))
                .toList();
        return loadBalancer.choose(route, healthy, clock.getAsLong());
    }
}
