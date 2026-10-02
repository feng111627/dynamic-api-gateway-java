package com.github.feng111627.dynamicapigatewayjava.admin;

import com.github.feng111627.dynamicapigatewayjava.health.HealthManager;
import com.github.feng111627.dynamicapigatewayjava.health.HealthStatus;
import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.model.GatewayConfig;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.routing.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.routing.UpdateResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for the gateway.
 *
 *  GET  /admin/config  - current active snapshot (version + rules)
 *  PUT  /admin/config  - atomic batch replace; 200 when the whole batch is live,
 *                        422 + issue list when rejected (old rules remain in force),
 *                        400 when the request body itself is not parseable/config-shaped
 *  GET  /admin/health  - observed status of every backend instance
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final RouteRegistry registry;
    private final RateLimiter rateLimiter;
    private final HealthManager healthManager;

    public AdminController(RouteRegistry registry, RateLimiter rateLimiter, HealthManager healthManager) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.healthManager = healthManager;
    }

    @GetMapping("/config")
    public GatewayConfig currentConfig() {
        return registry.current();
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody GatewayConfig candidate) {
        // Commit hooks run inside the registry's write lock together with the snapshot
        // publish: counters and the health registry therefore change exactly when the
        // new rules become visible, never in a half-applied window.
        UpdateResult result = registry.atomicUpdate(candidate, next -> {
            rateLimiter.reset();
            healthManager.syncInstances(new ArrayList<>(registry.activeBackends().values()));
        });

        Map<String, Object> body = new LinkedHashMap<>();
        if (result.accepted()) {
            body.put("accepted", true);
            body.put("version", result.activeConfig().version());
            body.put("routeCount", result.activeConfig().routes().size());
            body.put("message", "whole batch applied atomically; previous version fully replaced");
            return ResponseEntity.ok(body);
        }
        body.put("accepted", false);
        body.put("activeVersion", result.activeConfig().version());
        body.put("issues", result.issues());
        body.put("message", "batch rejected as a whole; previous rules remain in force");
        return ResponseEntity.unprocessableEntity().body(body);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", registry.version());
        List<Map<String, Object>> instances = new ArrayList<>();
        for (BackendInstance instance : registry.activeBackends().values()) {
            HealthStatus status = healthManager.statusOf(instance.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", instance.id());
            row.put("baseUrl", instance.baseUrl());
            row.put("status", status.name());
            row.put("statusChangedAtMs", healthManager.statusChangedAtMs(instance.id()));
            instances.add(row);
        }
        report.put("instances", instances);
        return report;
    }

    /** Distinguishes "the request itself is broken" (400) from "rules are illegal" (422). */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onUnreadable(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "accepted", false,
                "errorCategory", "REQUEST_BODY_INVALID",
                "code", "REQUEST_BODY_INVALID",
                "activeVersion", registry.version(),
                "message", "request body is not valid gateway config JSON: " + rootMessage(ex)));
    }

    private String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
