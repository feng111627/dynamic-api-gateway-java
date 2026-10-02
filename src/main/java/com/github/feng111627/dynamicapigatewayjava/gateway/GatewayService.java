package com.github.feng111627.dynamicapigatewayjava.gateway;

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
 * 网关编排：路由匹配 -> 限流 -> 粘性选实例 -> 转发，失败自动切换。
 *
 * 切换语义：每次转发尝试只发往一个实例；失败实例被标记不健康并解除粘性绑定后，
 * 才在剩余健康实例中选择下一个重试。任何时刻一个请求不会同时发往多个实例，
 * 且每次请求都以明确的 GatewayResponse 结束（成功或带分类的错误）。
 */
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final RouteRegistry routeRegistry;
    private final RateLimiter rateLimiter;
    private final StickySessionStore sessionStore;
    private final HealthRegistry healthRegistry;
    private final Forwarder forwarder;

    public GatewayService(RouteRegistry routeRegistry, RateLimiter rateLimiter,
                          StickySessionStore sessionStore, HealthRegistry healthRegistry,
                          Forwarder forwarder) {
        this.routeRegistry = routeRegistry;
        this.rateLimiter = rateLimiter;
        this.sessionStore = sessionStore;
        this.healthRegistry = healthRegistry;
        this.forwarder = forwarder;
    }

    public GatewayResponse handle(String path, String callerId, String body) {
        // 1. 路由匹配（读的是不可变快照，热更新不会造成半生效状态）
        Optional<Route> routeOpt = routeRegistry.match(path);
        if (routeOpt.isEmpty()) {
            return GatewayResponse.error(404, GatewayResponse.ErrorKind.ROUTE_NOT_FOUND,
                    "no route matches path: " + path);
        }
        Route route = routeOpt.get();

        // 2. 限流（路由 + 调用方维度）
        RateLimitDecision decision = rateLimiter.check(route.id(), callerId);
        if (!decision.allowed()) {
            return GatewayResponse.error(429, GatewayResponse.ErrorKind.RATE_LIMITED,
                    "rejected by rule " + decision.ruleId()
                            + " (route=" + route.id() + ", caller=" + callerId + ")");
        }

        // 3. 粘性选择 + 转发，失败自动切换
        return forwardWithFailover(route, path, callerId, body);
    }

    private GatewayResponse forwardWithFailover(Route route, String path, String callerId, String body) {
        List<String> tried = new ArrayList<>();
        String lastError = null;
        // 粘性按 (路由, 调用方) 维度维护，避免跨路由互相牵扯
        String sessionKey = route.id() + "|" + callerId;

        while (true) {
            Optional<BackendInstance> pick = pickBackend(route, sessionKey, tried);
            if (pick.isEmpty()) {
                // 所有实例都试过了：返回明确错误，不静默丢弃
                return GatewayResponse.error(503, GatewayResponse.ErrorKind.NO_HEALTHY_BACKEND,
                        "all backends failed for route " + route.id()
                                + ", tried=" + tried + ", lastError=" + lastError);
            }
            BackendInstance backend = pick.get();
            tried.add(backend.id());

            ForwardResult result = forwarder.forward(backend, path, body);
            if (result.success()) {
                // 成功：建立/刷新粘性绑定
                sessionStore.bind(sessionKey, backend.id());
                log.info("route={} caller={} -> backend={} status={}",
                        route.id(), callerId, backend.id(), result.statusCode());
                return GatewayResponse.ok(result.statusCode(), result.body(), backend.id());
            }

            // 失败：标记不健康、解除指向它的粘性绑定，再切到下一个实例
            log.warn("route={} caller={} backend={} failed: {}", route.id(), callerId,
                    backend.id(), result.error());
            healthRegistry.markUnhealthy(backend.id());
            sessionStore.evictIfBoundTo(sessionKey, backend.id());
            lastError = result.error();
        }
    }

    /**
     * 选实例：优先沿用未过期且健康的粘性绑定；否则选路由下第一个健康实例。
     * 实例恢复（重新标记健康）后，新绑定/过期重建的绑定可以再次落到它上面。
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
        return route.backends().stream()
                .filter(b -> !excluded.contains(b.id()))
                .filter(b -> healthRegistry.isHealthy(b.id()))
                .findFirst();
    }
}
