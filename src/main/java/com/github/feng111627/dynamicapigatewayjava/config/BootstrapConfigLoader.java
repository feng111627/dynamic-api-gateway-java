package com.github.feng111627.dynamicapigatewayjava.config;

import com.github.feng111627.dynamicapigatewayjava.backend.BackendSimulator;
import com.github.feng111627.dynamicapigatewayjava.forward.ForwardRequest;
import com.github.feng111627.dynamicapigatewayjava.forward.ForwardResponse;
import com.github.feng111627.dynamicapigatewayjava.forward.BackendForwarder;
import com.github.feng111627.dynamicapigatewayjava.health.HealthManager;
import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.model.GatewayConfig;
import com.github.feng111627.dynamicapigatewayjava.model.RouteRule;
import com.github.feng111627.dynamicapigatewayjava.model.ValidationIssue;
import com.github.feng111627.dynamicapigatewayjava.routing.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.routing.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * After the simulator instances are listening, installs the built-in demo routing config
 * (still through the fully validated atomic update path) and starts active health probing.
 * With the simulator disabled the gateway simply starts with an empty rule set and waits
 * for PUT /admin/config.
 */
@Component
public class BootstrapConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfigLoader.class);

    private final RouteRegistry registry;
    private final HealthManager healthManager;
    private final BackendSimulator simulator;
    private final BackendForwarder forwarder;
    private final GatewayProperties properties;

    public BootstrapConfigLoader(RouteRegistry registry, HealthManager healthManager,
                                 BackendSimulator simulator, BackendForwarder forwarder,
                                 GatewayProperties properties) {
        this.registry = registry;
        this.healthManager = healthManager;
        this.simulator = simulator;
        this.forwarder = forwarder;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        healthManager.start(this::probeHealth);
        if (!simulator.isEnabled()) {
            log.info("[gateway] backend simulator disabled; starting with empty routing config, "
                    + "use PUT /admin/config to load rules");
            return;
        }
        GatewayConfig demo = buildDemoConfig();
        UpdateResult result = registry.atomicUpdate(demo, next ->
                healthManager.syncInstances(new ArrayList<>(registry.activeBackends().values())));
        if (result.accepted()) {
            log.info("[gateway] demo routing config applied atomically, version={}, routes={}",
                    result.activeConfig().version(), result.activeConfig().routes().size());
            registry.activeBackends().forEach((id, backend) ->
                    log.info("[gateway] backend registered: id={} url={}", id, backend.baseUrl()));
        } else {
            for (ValidationIssue issue : result.issues()) {
                log.error("[gateway] demo config rejected: [{}] {}", issue.code(), issue.message());
            }
        }
    }

    private boolean probeHealth(String baseUrl) {
        ForwardResponse response = forwarder.forward(
                new BackendInstance("probe", baseUrl),
                new ForwardRequest("GET", "/health", null, Map.of(), new byte[0]));
        return !response.transportError() && response.statusCode() == 200;
    }

    private GatewayConfig buildDemoConfig() {
        List<BackendInstance> backends = new ArrayList<>();
        for (Map.Entry<String, String> entry : simulator.baseUrls().entrySet()) {
            backends.add(new BackendInstance(entry.getKey(), entry.getValue()));
        }

        RouteRule orders = new RouteRule("orders", "/orders", backends, true,
                new RouteRule.RateLimitSpec(properties.getDemoRouteLimit(),
                        properties.getDemoCallerLimit(), properties.getDemoWindowSeconds()));
        RouteRule products = new RouteRule("products", "/products", backends, false,
                new RouteRule.RateLimitSpec(properties.getDemoRouteLimit(), null,
                        properties.getDemoWindowSeconds()));
        return new GatewayConfig(0, List.of(orders, products));
    }
}
