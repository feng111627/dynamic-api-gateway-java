package com.github.feng111627.dynamicapigatewayjava.config;

import com.github.feng111627.dynamicapigatewayjava.core.GatewayDispatcher;
import com.github.feng111627.dynamicapigatewayjava.forward.BackendForwarder;
import com.github.feng111627.dynamicapigatewayjava.health.HealthManager;
import com.github.feng111627.dynamicapigatewayjava.loadbalance.RoundRobinSelector;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.routing.ConfigValidator;
import com.github.feng111627.dynamicapigatewayjava.routing.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.session.StickySessionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayBeans {

    @Bean
    public ConfigValidator configValidator() {
        return new ConfigValidator();
    }

    @Bean
    public RouteRegistry routeRegistry(ConfigValidator validator) {
        return new RouteRegistry();
    }

    @Bean
    public RateLimiter rateLimiter() {
        return new RateLimiter();
    }

    @Bean
    public HealthManager healthManager(GatewayProperties properties) {
        return new HealthManager(properties.getUnhealthyThreshold(),
                properties.getHealthyThreshold(), properties.getHealthProbeIntervalMs());
    }

    @Bean
    public RoundRobinSelector roundRobinSelector() {
        return new RoundRobinSelector();
    }

    @Bean
    public StickySessionStore stickySessionStore() {
        return new StickySessionStore();
    }

    @Bean
    public BackendForwarder backendForwarder(GatewayProperties properties) {
        return new BackendForwarder(properties.getForwardTimeoutMs());
    }

    @Bean
    public GatewayDispatcher gatewayDispatcher(RouteRegistry registry, RateLimiter rateLimiter,
                                               HealthManager healthManager, StickySessionStore stickyStore,
                                               RoundRobinSelector selector, BackendForwarder forwarder,
                                               GatewayProperties properties) {
        return new GatewayDispatcher(registry, rateLimiter, healthManager, stickyStore, selector, forwarder, properties);
    }
}
