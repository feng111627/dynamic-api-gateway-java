package com.github.feng111627.dynamicapigatewayjava.web;

import com.github.feng111627.dynamicapigatewayjava.balance.InstanceConcurrencyLimiter;
import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalanceStrategy;
import com.github.feng111627.dynamicapigatewayjava.balance.LoadBalancer;
import com.github.feng111627.dynamicapigatewayjava.gateway.Forwarder;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayService;
import com.github.feng111627.dynamicapigatewayjava.health.HealthProbe;
import com.github.feng111627.dynamicapigatewayjava.health.HealthRecoveryService;
import com.github.feng111627.dynamicapigatewayjava.health.HttpHealthProbe;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimitRule;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.StickySessionStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 网关核心组件装配。 */
@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    @Bean
    public RouteRegistry routeRegistry(
            @Value("${gateway.backend1.port:18081}") int backend1Port,
            @Value("${gateway.backend2.port:18082}") int backend2Port,
            @Value("${gateway.backend1.weight:1}") int w1,
            @Value("${gateway.backend2.weight:1}") int w2,
            @Value("${gateway.backend1.max-concurrency:0}") int c1,
            @Value("${gateway.backend2.max-concurrency:0}") int c2) {
        // 初始路由：/api/ 前缀转发到两个本地后端实例（权重/并发上限可配置）
        Route defaultRoute = new Route("default", "/api/", List.of(
                new BackendInstance("backend-1", "127.0.0.1", backend1Port, w1, c1),
                new BackendInstance("backend-2", "127.0.0.1", backend2Port, w2, c2)));
        return new RouteRegistry(List.of(defaultRoute));
    }

    @Bean
    public RateLimiter rateLimiter() {
        // 默认规则：每个调用方在每个路由下 60 次/分钟
        return new RateLimiter(List.of(
                new RateLimitRule("per-caller-per-route", "*", "*", 60, 60_000)));
    }

    @Bean
    public StickySessionStore stickySessionStore(
            @Value("${gateway.session.ttl-millis:300000}") long ttlMillis) {
        return new StickySessionStore(ttlMillis);
    }

    @Bean
    public HealthRegistry healthRegistry() {
        return new HealthRegistry();
    }

    @Bean
    public LoadBalancer loadBalancer(
            HealthRegistry healthRegistry,
            @Value("${gateway.recovery.share-divisor:4}") int shareDivisor) {
        // 翻转时均衡器重置轮询的联动在 GatewayService 内完成；这里仅保留健康变化的可观察日志
        healthRegistry.addListener(change -> log.info("HEALTH-CHANGE backend={} state={} at={}",
                change.backendId(), change.state(), change.atMillis()));
        return new LoadBalancer(LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN, shareDivisor);
    }

    @Bean
    public InstanceConcurrencyLimiter concurrencyLimiter() {
        return new InstanceConcurrencyLimiter();
    }

    @Bean
    public HealthProbe healthProbe() {
        return new HttpHealthProbe();
    }

    @Bean
    public HealthRecoveryService healthRecoveryService(
            HealthRegistry healthRegistry, HealthProbe healthProbe, RouteRegistry routeRegistry,
            LoadBalancer loadBalancer,
            @Value("${gateway.recovery.probe-interval-millis:1000}") long probeInterval,
            @Value("${gateway.recovery.warmup-millis:5000}") long warmup) {
        HealthRecoveryService service = new HealthRecoveryService(
                healthRegistry, healthProbe, routeRegistry, loadBalancer,
                probeInterval, warmup, System::currentTimeMillis);
        service.start();
        return service;
    }

    @Bean
    public Forwarder forwarder() {
        return new Forwarder();
    }

    @Bean
    public GatewayService gatewayService(RouteRegistry routeRegistry, RateLimiter rateLimiter,
                                         StickySessionStore sessionStore,
                                         HealthRegistry healthRegistry, Forwarder forwarder,
                                         LoadBalancer loadBalancer,
                                         InstanceConcurrencyLimiter concurrencyLimiter,
                                         @Value("${gateway.capacity.acquire-timeout-millis:200}")
                                                 long acquireTimeoutMillis) {
        return new GatewayService(routeRegistry, rateLimiter, sessionStore, healthRegistry, forwarder,
                loadBalancer, concurrencyLimiter, acquireTimeoutMillis, System::currentTimeMillis);
    }
}
