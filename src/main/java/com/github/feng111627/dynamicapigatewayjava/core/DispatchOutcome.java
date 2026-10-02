package com.github.feng111627.dynamicapigatewayjava.core;

import com.github.feng111627.dynamicapigatewayjava.forward.ForwardResponse;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimitDecision;

import java.util.List;

/**
 * One request ends with exactly one of these outcomes — no silent drops:
 *  OK / UPSTREAM_RESPONSE  - forwarded (response may still carry an upstream 4xx/5xx)
 *  NO_ROUTE                - no rule matches
 *  RATE_LIMITED            - rejected by a named rate-limit rule
 *  NO_HEALTHY_INSTANCE     - rule matches but no backend is usable
 *  ALL_INSTANCES_FAILED    - every tryable backend failed this request
 */
public record DispatchOutcome(
        String status,
        int httpStatus,
        String code,
        String message,
        String routeId,
        String instanceId,
        List<String> attemptedInstances,
        boolean stickyReattached,
        RateLimitDecision rateLimitDecision,
        ForwardResponse upstreamResponse
) {
    public static DispatchOutcome noRoute(String path) {
        return new DispatchOutcome("NO_ROUTE", 404, "NO_ROUTE",
                "no route matches path: " + path, null, null, List.of(), false, null, null);
    }

    public static DispatchOutcome rateLimited(RateLimitDecision decision) {
        String ruleName = decision.scope() == com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimitScope.ROUTE
                ? "route:" + decision.routeId()
                : "caller:" + decision.callerId();
        return new DispatchOutcome("RATE_LIMITED", 429, "RATE_LIMITED",
                "rejected by rate-limit rule '" + ruleName + "' (" + decision.scope()
                        + "): limit=" + decision.limit() + "/window used=" + decision.used(),
                decision.routeId(), null, List.of(), false, decision, null);
    }

    public static DispatchOutcome noHealthyInstance(String routeId) {
        return new DispatchOutcome("NO_HEALTHY_INSTANCE", 502, "NO_HEALTHY_INSTANCE",
                "no healthy backend instance for route " + routeId,
                routeId, null, List.of(), false, null, null);
    }
}
