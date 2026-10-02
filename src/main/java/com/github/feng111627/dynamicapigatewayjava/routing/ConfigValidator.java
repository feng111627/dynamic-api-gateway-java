package com.github.feng111627.dynamicapigatewayjava.routing;

import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.model.GatewayConfig;
import com.github.feng111627.dynamicapigatewayjava.model.RouteRule;
import com.github.feng111627.dynamicapigatewayjava.model.ValidationIssue;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a whole candidate config independently of the currently active config.
 * Every problem found is returned, so callers can reject the whole batch with a
 * complete, classifiable issue list.
 *
 * Issue codes (stable, callers can branch on them):
 *   EMPTY_ROUTE_SET
 *   ROUTE_ID_MISSING / DUPLICATE_ROUTE_ID
 *   PATH_PREFIX_MISSING / PATH_PREFIX_INVALID
 *   BACKEND_EMPTY / BACKEND_ID_MISSING / DUPLICATE_BACKEND_ID / BACKEND_URL_INVALID
 *   BACKEND_CONFLICT        - same backend id mapped to different urls across routes
 *   RATE_LIMIT_WINDOW_MISSING / RATE_LIMIT_LIMIT_INVALID / RATE_LIMIT_WINDOW_INVALID
 */
public class ConfigValidator {

    private static final int MAX_WINDOW_SECONDS = 3600;

    public List<ValidationIssue> validate(GatewayConfig config) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (config == null) {
            issues.add(new ValidationIssue("EMPTY_ROUTE_SET", "config must not be null"));
            return issues;
        }
        List<RouteRule> routes = config.routes();
        if (routes == null || routes.isEmpty()) {
            issues.add(new ValidationIssue("EMPTY_ROUTE_SET", "at least one route is required; refusing to wipe all routes"));
            return issues;
        }

        Set<String> routeIds = new HashSet<>();
        Map<String, String> backendBaseUrlById = new HashMap<>();

        for (RouteRule route : routes) {
            validateRoute(route, routeIds, backendBaseUrlById, issues);
        }
        return List.copyOf(issues);
    }

    private void validateRoute(RouteRule route, Set<String> routeIds,
                               Map<String, String> backendBaseUrlById,
                               List<ValidationIssue> issues) {
        if (route == null) {
            issues.add(new ValidationIssue("ROUTE_INVALID", "route entry must not be null"));
            return;
        }
        String routeId = route.id();
        if (routeId == null || routeId.isBlank()) {
            issues.add(new ValidationIssue("ROUTE_ID_MISSING", "a route has no id"));
            routeId = routeId == null ? "<null>" : "<blank>";
        } else if (!routeIds.add(routeId)) {
            issues.add(new ValidationIssue("DUPLICATE_ROUTE_ID", "duplicate route id: " + routeId));
        }

        String prefix = route.pathPrefix();
        if (prefix == null || prefix.isBlank()) {
            issues.add(new ValidationIssue("PATH_PREFIX_MISSING", "route '" + routeId + "' has no pathPrefix"));
        } else if (!prefix.startsWith("/")) {
            issues.add(new ValidationIssue("PATH_PREFIX_INVALID",
                    "route '" + routeId + "' pathPrefix must start with '/': " + prefix));
        }

        validateBackends(routeId, route.backends(), backendBaseUrlById, issues);
        validateRateLimit(routeId, route.rateLimit(), issues);
    }

    private void validateBackends(String routeId, List<BackendInstance> backends,
                                  Map<String, String> backendBaseUrlById,
                                  List<ValidationIssue> issues) {
        if (backends == null || backends.isEmpty()) {
            issues.add(new ValidationIssue("BACKEND_EMPTY", "route '" + routeId + "' must have at least one backend"));
            return;
        }
        Set<String> ids = new HashSet<>();
        for (BackendInstance backend : backends) {
            if (backend == null) {
                issues.add(new ValidationIssue("BACKEND_INVALID", "route '" + routeId + "' has a null backend"));
                continue;
            }
            String id = backend.id();
            if (id == null || id.isBlank()) {
                issues.add(new ValidationIssue("BACKEND_ID_MISSING", "route '" + routeId + "' has a backend without id"));
                continue;
            }
            if (!ids.add(id)) {
                issues.add(new ValidationIssue("DUPLICATE_BACKEND_ID",
                        "route '" + routeId + "' has duplicated backend id: " + id));
            }
            String url = backend.baseUrl();
            if (!isValidHttpUrl(url)) {
                issues.add(new ValidationIssue("BACKEND_URL_INVALID",
                        "route '" + routeId + "' backend '" + id + "' has invalid baseUrl: " + url));
            } else {
                String previous = backendBaseUrlById.putIfAbsent(id, url);
                if (previous != null && !previous.equals(url)) {
                    issues.add(new ValidationIssue("BACKEND_CONFLICT",
                            "backend id '" + id + "' is mapped to both " + previous + " and " + url));
                }
            }
        }
    }

    private void validateRateLimit(String routeId, RouteRule.RateLimitSpec spec, List<ValidationIssue> issues) {
        if (spec == null) {
            return;
        }
        Integer routeLimit = spec.routeLimit();
        Integer callerLimit = spec.callerLimit();
        Integer window = spec.windowSeconds();
        boolean hasLimit = routeLimit != null || callerLimit != null;

        if (hasLimit && window == null) {
            issues.add(new ValidationIssue("RATE_LIMIT_WINDOW_MISSING",
                    "route '" + routeId + "' defines a limit but no windowSeconds"));
        }
        if (routeLimit != null && routeLimit <= 0) {
            issues.add(new ValidationIssue("RATE_LIMIT_LIMIT_INVALID",
                    "route '" + routeId + "' routeLimit must be positive: " + routeLimit));
        }
        if (callerLimit != null && callerLimit <= 0) {
            issues.add(new ValidationIssue("RATE_LIMIT_LIMIT_INVALID",
                    "route '" + routeId + "' callerLimit must be positive: " + callerLimit));
        }
        if (window != null && (window <= 0 || window > MAX_WINDOW_SECONDS)) {
            issues.add(new ValidationIssue("RATE_LIMIT_WINDOW_INVALID",
                    "route '" + routeId + "' windowSeconds must be within 1.." + MAX_WINDOW_SECONDS + ": " + window));
        }
    }

    private boolean isValidHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getPort() >= 0;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
