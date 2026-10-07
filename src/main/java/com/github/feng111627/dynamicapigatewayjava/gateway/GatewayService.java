package com.github.feng111627.dynamicapigatewayjava.gateway;

import com.github.feng111627.dynamicapigatewayjava.balance.ConcurrencyLimiter;
import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalancer;
import com.github.feng111627.dynamicapigatewayjava.balance.WeightedRoundRobinLoadBalancer;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关编排：路由匹配 -> 限流 -> 粘性/负载均衡选实例 -> 并发上限 -> 透传转发，失败自动切换。
 *
 * 切换语义：每次转发尝试只发往一个实例；失败实例被标记不健康并解除粘性绑定后，
 * 才在剩余健康实例中选择下一个重试。任何时刻一个请求不会同时发往多个实例，
 * 且每次请求都以明确的 GatewayResponse 结束（成功或带分类的错误）。
 *
 * 容量语义：实例并发上限满时不超发、不静默丢弃——先尝试其他健康实例，
 * 所有健康实例都满时返回 503 BACKEND_AT_CAPACITY（与限流 429、无健康后端 503 可区分）。
 */
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final RouteRegistry routeRegistry;
    private final RateLimiter rateLimiter;
    private final StickySessionStore sessionStore;
    private final HealthRegistry healthRegistry;
    private final Forwarder forwarder;
    private final LoadBalancer loadBalancer;
    private final ConcurrencyLimiter concurrencyLimiter;

    /** 兼容装配：默认加权轮询（按实例权重）+ 不限并发。 */
    public GatewayService(RouteRegistry routeRegistry, RateLimiter rateLimiter,
                          StickySessionStore sessionStore, HealthRegistry healthRegistry,
                          Forwarder forwarder) {
        this(routeRegistry, rateLimiter, sessionStore, healthRegistry, forwarder,
                new WeightedRoundRobinLoadBalancer(), new ConcurrencyLimiter());
    }

    public GatewayService(RouteRegistry routeRegistry, RateLimiter rateLimiter,
                          StickySessionStore sessionStore, HealthRegistry healthRegistry,
                          Forwarder forwarder, LoadBalancer loadBalancer,
                          ConcurrencyLimiter concurrencyLimiter) {
        this.routeRegistry = routeRegistry;
        this.rateLimiter = rateLimiter;
        this.sessionStore = sessionStore;
        this.healthRegistry = healthRegistry;
        this.forwarder = forwarder;
        this.loadBalancer = loadBalancer;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /** 兼容旧入口：等价于一个不带查询串和自定义头的 POST。 */
    public GatewayResponse handle(String path, String callerId, String body) {
        return handle(GatewayRequest.post(path, callerId, body));
    }

    public GatewayResponse handle(GatewayRequest req) {
        // 1. 路由匹配（读的是不可变快照，热更新不会造成半生效状态）
        Optional<Route> routeOpt = routeRegistry.match(req.path());
        if (routeOpt.isEmpty()) {
            return GatewayResponse.error(404, GatewayResponse.ErrorKind.ROUTE_NOT_FOUND,
                    "no route matches path: " + req.path());
        }
        Route route = routeOpt.get();

        // 2. 限流（路由 + 调用方维度）
        RateLimitDecision decision = rateLimiter.check(route.id(), req.callerId());
        if (!decision.allowed()) {
            return GatewayResponse.error(429, GatewayResponse.ErrorKind.RATE_LIMITED,
                    "rejected by rule " + decision.ruleId()
                            + " (route=" + route.id() + ", caller=" + req.callerId() + ")");
        }

        // 3. 粘性/负载均衡选择 + 并发上限 + 透传转发，失败自动切换
        return forwardWithFailover(route, req);
    }

    private GatewayResponse forwardWithFailover(Route route, GatewayRequest req) {
        List<String> tried = new ArrayList<>();
        List<String> atCapacity = new ArrayList<>();
        String lastError = null;
        // 粘性按 (路由, 调用方) 维度维护，避免跨路由互相牵扯
        String sessionKey = route.id() + "|" + req.callerId();

        while (true) {
            Optional<BackendInstance> pick = pickBackend(route, sessionKey, tried, atCapacity);
            if (pick.isEmpty()) {
                if (!atCapacity.isEmpty() && tried.isEmpty()) {
                    // 健康实例都在，只是全部达到并发上限：明确拒绝，可重试
                    return GatewayResponse.error(503, GatewayResponse.ErrorKind.BACKEND_AT_CAPACITY,
                            "all healthy backends at concurrency limit for route " + route.id()
                                    + ", atCapacity=" + atCapacity);
                }
                if (!atCapacity.isEmpty()) {
                    // 部分实例失败、其余满员：也按容量拒绝，语义比 NO_HEALTHY_BACKEND 更准
                    return GatewayResponse.error(503, GatewayResponse.ErrorKind.BACKEND_AT_CAPACITY,
                            "remaining healthy backends at concurrency limit for route " + route.id()
                                    + ", tried=" + tried + ", atCapacity=" + atCapacity
                                    + ", lastError=" + lastError);
                }
                // 所有实例都试过了：返回明确错误，不静默丢弃
                return GatewayResponse.error(503, GatewayResponse.ErrorKind.NO_HEALTHY_BACKEND,
                        "all backends failed for route " + route.id()
                                + ", tried=" + tried + ", lastError=" + lastError);
            }
            BackendInstance backend = pick.get();

            if (!concurrencyLimiter.tryAcquire(backend.id(), backend.maxConcurrency())) {
                // 该实例已满：不超发，换下一个健康实例
                atCapacity.add(backend.id());
                continue;
            }
            tried.add(backend.id());

            ForwardResult result;
            try {
                result = forwarder.forward(backend, req);
            } finally {
                concurrencyLimiter.release(backend.id());
            }
            if (result.success()) {
                // 成功：只有"沿用/新建粘性绑定落点"时才刷新绑定。
                // 若本次是因为绑定实例打满而临时溢出到别的健康实例，不改绑也不续期——
                // 打满不是故障，绑定实例腾出余量后后续请求应自然回到它上面。
                String boundId = sessionStore.find(sessionKey)
                        .map(StickySession::backendId).orElse(null);
                if (boundId == null || boundId.equals(backend.id())) {
                    sessionStore.bind(sessionKey, backend.id());
                } else {
                    log.info("route={} caller={} sticky={} at capacity, temporarily served by={}",
                            route.id(), req.callerId(), boundId, backend.id());
                }
                log.info("route={} caller={} -> backend={} status={}",
                        route.id(), req.callerId(), backend.id(), result.statusCode());
                return GatewayResponse.ok(result.statusCode(), result.body(),
                        result.headers(), backend.id());
            }

            // 失败：标记不健康、解除指向它的粘性绑定，再切到下一个实例
            log.warn("route={} caller={} backend={} failed: {}", route.id(), req.callerId(),
                    backend.id(), result.error());
            healthRegistry.markUnhealthy(backend.id());
            sessionStore.evictIfBoundTo(sessionKey, backend.id());
            lastError = result.error();
        }
    }

    /**
     * 选实例：优先沿用未过期且健康的粘性绑定；否则交给负载均衡策略在健康实例中按权重分散。
     * 实例恢复（重新标记健康）后，新绑定/过期重建的绑定可以再次落到它上面。
     *
     * 容量语义：粘性绑定的实例若本次已确认打满（在 atCapacity 中），不能原样返回它，
     * 否则调用方会在"选中 -> tryAcquire 失败"之间无限空转、请求永不返回。
     * 此时回落到负载均衡，在仍有余量的健康实例里按权重挑一个临时承接（不改粘性绑定：
     * 打满不是故障，不摘健康、不踢会话；绑定实例腾出余量后后续请求自然回到它上面）。
     */
    private Optional<BackendInstance> pickBackend(Route route, String sessionKey,
                                                  List<String> excluded, List<String> atCapacity) {
        Optional<StickySession> sticky = sessionStore.find(sessionKey);
        if (sticky.isPresent()) {
            String boundId = sticky.get().backendId();
            Optional<BackendInstance> bound = route.backends().stream()
                    .filter(b -> b.id().equals(boundId))
                    .filter(b -> !excluded.contains(b.id()))
                    .filter(b -> !atCapacity.contains(b.id()))
                    .findFirst();
            if (bound.isPresent() && healthRegistry.isHealthy(boundId)) {
                return bound;
            }
        }
        List<BackendInstance> eligible = route.backends().stream()
                .filter(b -> !excluded.contains(b.id()))
                .filter(b -> !atCapacity.contains(b.id()))
                .filter(b -> healthRegistry.isHealthy(b.id()))
                .toList();
        return loadBalancer.select(route.id(), eligible);
    }
}
