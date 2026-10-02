package com.github.feng111627.dynamicapigatewayjava.web;

import com.github.feng111627.dynamicapigatewayjava.gateway.Forwarder;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayService;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimitRule;
import com.github.feng111627.dynamicapigatewayjava.limit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import com.github.feng111627.dynamicapigatewayjava.sticky.StickySessionStore;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 网关核心组件装配。 */
@Configuration
public class GatewayConfig {

    @Bean
    public RouteRegistry routeRegistry(
            @Value("${gateway.backend1.port:18081}") int backend1Port,
            @Value("${gateway.backend2.port:18082}") int backend2Port) {
        // 初始路由：/api/ 前缀转发到两个本地后端实例
        Route defaultRoute = new Route("default", "/api/", List.of(
                new BackendInstance("backend-1", "127.0.0.1", backend1Port),
                new BackendInstance("backend-2", "127.0.0.1", backend2Port)));
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
    public Forwarder forwarder() {
        return new Forwarder();
    }

    @Bean
    public GatewayService gatewayService(RouteRegistry routeRegistry, RateLimiter rateLimiter,
                                         StickySessionStore sessionStore,
                                         HealthRegistry healthRegistry, Forwarder forwarder) {
        return new GatewayService(routeRegistry, rateLimiter, sessionStore, healthRegistry, forwarder);
    }
}
