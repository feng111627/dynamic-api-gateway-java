package com.github.feng111627.dynamicapigatewayjava.core;

import com.github.feng111627.dynamicapigatewayjava.config.GatewayProperties;
import com.github.feng111627.dynamicapigatewayjava.forward.BackendForwarder;
import com.github.feng111627.dynamicapigatewayjava.forward.ForwardRequest;
import com.github.feng111627.dynamicapigatewayjava.forward.ForwardResponse;
import com.github.feng111627.dynamicapigatewayjava.health.HealthManager;
import com.github.feng111627.dynamicapigatewayjava.loadbalance.RoundRobinSelector;
import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.model.RouteRule;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimitDecision;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimiter;
import com.github.feng111627.dynamicapigatewayjava.routing.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.session.StickyEntry;
import com.github.feng111627.dynamicapigatewayjava.session.StickySessionStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates one request end to end: route match → rate limit → instance choice
 * (sticky aware) → forward with bounded failover.
 *
 * Delivery guarantees required by the spec:
 *  - each request returns an explicit outcome (forwarded / rate limited / no route /
 *    no healthy instance / all failed); nothing is dropped silently;
 *  - on failure the request is sent to each chosen instance at most once; failover
 *    never retries the same instance and therefore never double-delivers;
 *  - non-idempotent methods are never retried on an upstream 5xx (the single attempted
 *    response is returned as the request's definite result);
 *  - sticky callers keep their instance while healthy, move once when it fails, and may
 *    move back after recovery only after a backoff delay to avoid flapping.
 */
public class GatewayDispatcher {

    private final RouteRegistry registry;
    private final RateLimiter rateLimiter;
    private final HealthManager healthManager;
    private final StickySessionStore stickyStore;
    private final RoundRobinSelector selector;
    private final BackendForwarder forwarder;
    private final GatewayProperties properties;

    public GatewayDispatcher(RouteRegistry registry, RateLimiter rateLimiter, HealthManager healthManager,
                             StickySessionStore stickyStore, RoundRobinSelector selector,
                             BackendForwarder forwarder, GatewayProperties properties) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.healthManager = healthManager;
        this.stickyStore = stickyStore;
        this.selector = selector;
        this.forwarder = forwarder;
        this.properties = properties;
    }

    public DispatchOutcome dispatch(ForwardRequest request, String callerId, long nowMs) {
        RouteRule route = registry.match(request.path());
        if (route == null) {
            return DispatchOutcome.noRoute(request.path());
        }

        // Route-level quota first, then caller-level: the first rejection returned names
        // exactly which rule stopped the request.
        RouteRule.RateLimitSpec spec = route.rateLimit();
        if (spec != null) {
            if (spec.routeLimit() != null) {
                RateLimitDecision decision = rateLimiter.checkRoute(
                        route.id(), spec.routeLimit(), spec.windowSeconds(), nowMs);
                if (!decision.allowed()) {
                    return DispatchOutcome.rateLimited(decision);
                }
            }
            if (spec.callerLimit() != null) {
                RateLimitDecision decision = rateLimiter.checkCaller(
                        route.id(), callerId, spec.callerLimit(), spec.windowSeconds(), nowMs);
                if (decision != null && !decision.allowed()) {
                    return DispatchOutcome.rateLimited(decision);
                }
            }
        }

        List<BackendInstance> routeBackends = route.backends();
        List<BackendInstance> healthy = routeBackends.stream()
                .filter(healthManager::isHealthy)
                .toList();
        if (healthy.isEmpty()) {
            return DispatchOutcome.noHealthyInstance(route.id());
        }

        StickyEntry entry = route.stickySession() && callerId != null && !callerId.isBlank()
                ? stickyStore.get(route.id(), callerId, nowMs)
                : null;

        // The attempt order is planned once and contains every backend at most once, so a
        // single request can never be sent to the same instance twice. The sticky target is
        // always first even if it flipped unhealthy during planning (the request already
        // decided to try it); later candidates are re-checked for health at failover time.
        List<BackendInstance> attemptOrder = planAttemptOrder(route, routeBackends, healthy, entry, nowMs);
        boolean reattached = false;
        List<String> attempted = new ArrayList<>();

        for (int i = 0; i < attemptOrder.size(); i++) {
            BackendInstance instance = attemptOrder.get(i);
            if (i > 0 && !healthManager.isHealthy(instance)) {
                // Became unhealthy while earlier attempts were in flight; do not waste this
                // request on it (it has never been tried in THIS request).
                continue;
            }
            ForwardResponse response = forwarder.forward(instance, request);
            attempted.add(instance.id());

            boolean transportFailed = response.transportError();
            boolean upstreamServerError = !transportFailed && response.statusCode() >= 500;
            if (!transportFailed && !upstreamServerError) {
                healthManager.onForwardSuccess(instance);
                StickyEntry boundEntry = persistStickyBinding(route, callerId, entry, instance, nowMs);
                if (boundEntry != null && boundEntry.reattachGeneration()
                        > (entry == null ? -1 : entry.reattachGeneration())) {
                    reattached = true;
                }
                entry = boundEntry;
                return new DispatchOutcome("UPSTREAM_RESPONSE", response.statusCode(), "OK",
                        "forwarded to " + instance.id(), route.id(), instance.id(),
                        List.copyOf(attempted), reattached, null, response);
            }

            // Failure: trip (or reinforce) passive health, record sticky failure episode.
            healthManager.onForwardFailure(instance);
            if (entry != null && instance.id().equals(entry.originalInstanceId())) {
                entry = stickyStore.markOriginalFailed(entry, nowMs);
            }

            boolean hasNextTryable = hasNextTryable(attemptOrder, i);
            boolean retryable = transportFailed
                    || (upstreamServerError && properties.isRetryGetOn5xx()
                        && isIdempotent(request.method()) && hasNextTryable);
            if (!retryable) {
                // Definite terminal result for this request (e.g. POST after 5xx): never
                // double-send a non-idempotent call, but still return an explicit outcome.
                int httpStatus = transportFailed ? 502 : response.statusCode();
                return new DispatchOutcome("UPSTREAM_RESPONSE", httpStatus,
                        transportFailed ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_ERROR",
                        transportFailed
                                ? "instance " + instance.id() + " connection failed: " + response.errorMessage()
                                : "instance " + instance.id() + " returned " + response.statusCode(),
                        route.id(), instance.id(), List.copyOf(attempted), false, null, response);
            }
            // otherwise continue to the next pre-planned healthy instance
        }

        return new DispatchOutcome("ALL_INSTANCES_FAILED", 502, "ALL_INSTANCES_FAILED",
                "all " + attempted.size() + " candidate instance(s) failed: " + attempted,
                route.id(), null, List.copyOf(attempted), false, null, null);
    }

    private boolean hasNextTryable(List<BackendInstance> order, int index) {
        for (int j = index + 1; j < order.size(); j++) {
            if (healthManager.isHealthy(order.get(j))) {
                return true;
            }
        }
        return false;
    }

    private boolean isIdempotent(String method) {
        return method != null && (method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("HEAD"));
    }

    /**
     * Build the no-duplicate attempt order up front from the healthy snapshot, so even if
     * health transitions during the request an instance cannot be tried twice.
     */
    private List<BackendInstance> planAttemptOrder(RouteRule route, List<BackendInstance> routeBackends,
                                                   List<BackendInstance> healthy,
                                                   StickyEntry entry, long nowMs) {
        Set<BackendInstance> ordered = new LinkedHashSet<>();
        if (entry == null || !route.stickySession()) {
            BackendInstance picked = pickRoundRobin(healthy);
            if (picked != null) {
                ordered.add(picked);
            }
            routeBackends.forEach(ordered::add);
            return List.copyOf(ordered);
        }

        Map<String, BackendInstance> byId = routeBackends.stream()
                .collect(Collectors.toMap(BackendInstance::id, Function.identity(), (a, b) -> a));
        BackendInstance original = byId.get(entry.originalInstanceId());
        BackendInstance current = byId.get(entry.currentInstanceId());

        boolean reattachNow = original != null
                && (current == null || !original.id().equals(entry.currentInstanceId()))
                && entry.originalFailedAtMs() != 0L
                && nowMs - healthManager.statusChangedAtMs(original.id()) >= properties.getStickyReattachBackoffMs();

        if (reattachNow) {
            ordered.add(original);
            if (current != null) {
                ordered.add(current);
            }
            routeBackends.forEach(ordered::add);
            return List.copyOf(ordered);
        }

        // Stay on current failover target while original is missing/unhealthy/backing off.
        if (current != null) {
            ordered.add(current);
        }
        routeBackends.forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private BackendInstance pickRoundRobin(List<BackendInstance> healthy) {
        return selector.select(healthy);
    }

    private StickyEntry persistStickyBinding(RouteRule route, String callerId, StickyEntry existing,
                                             BackendInstance instance, long nowMs) {
        if (!route.stickySession() || callerId == null || callerId.isBlank()) {
            return null;
        }
        if (existing == null) {
            return stickyStore.save(new StickyEntry(route.id(), callerId,
                    instance.id(), instance.id(), nowMs, nowMs + properties.getStickyTtlMs(), 0L, 0));
        }
        if (instance.id().equals(existing.originalInstanceId())
                && !instance.id().equals(existing.currentInstanceId())) {
            // Current forward went to the recovered original after failover.
            return stickyStore.reattachToOriginal(existing, nowMs, properties.getStickyTtlMs());
        }
        if (instance.id().equals(existing.currentInstanceId())) {
            // Keep affinity; slide TTL on activity.
            return stickyStore.touch(existing, nowMs, properties.getStickyTtlMs());
        }
        // Binding changed to a different instance (failover target that handled the call).
        return stickyStore.rebind(route.id(), callerId, instance.id(), nowMs, properties.getStickyTtlMs());
    }
}
