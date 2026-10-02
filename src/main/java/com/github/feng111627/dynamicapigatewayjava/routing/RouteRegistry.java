package com.github.feng111627.dynamicapigatewayjava.routing;

import com.github.feng111627.dynamicapigatewayjava.model.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.model.GatewayConfig;
import com.github.feng111627.dynamicapigatewayjava.model.RouteRule;
import com.github.feng111627.dynamicapigatewayjava.model.ValidationIssue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Holds the currently active routing config and swaps new configs in atomically.
 *
 * Atomicity semantics: a candidate batch is validated independently of the active config.
 * If validation finds anything wrong, the active reference is never touched and the call
 * returns the full issue list. Only when the whole candidate is legal do we build a new
 * immutable {@link GatewayConfig} snapshot, assign it the next version and publish it with
 * a single volatile write plus optional commit hooks under one write lock. In-flight and
 * subsequent requests therefore always observe either the old complete rule set or the new
 * complete rule set — never a partially applied mixture.
 */
public class RouteRegistry {

    private final ConfigValidator validator = new ConfigValidator();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile GatewayConfig current = new GatewayConfig(0, List.of());

    public GatewayConfig current() {
        return current;
    }

    public long version() {
        return current.version();
    }

    /**
     * Longest-prefix match; {@code path} never observes a half-swapped config because the
     * snapshot reference is fetched once and the returned rule belongs to that snapshot.
     */
    public RouteRule match(String path) {
        GatewayConfig snapshot = current;
        RouteRule matched = null;
        for (RouteRule route : snapshot.routes()) {
            if (matchesPrefix(path, route.pathPrefix())
                    && (matched == null || route.pathPrefix().length() > matched.pathPrefix().length())) {
                matched = route;
            }
        }
        return matched;
    }

    private boolean matchesPrefix(String path, String prefix) {
        if (path == null || prefix == null) {
            return false;
        }
        return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }

    /**
     * Attempt an atomic batch update.
     *
     * @param candidate       the proposed rules (version field is ignored; server assigns it)
     * @param onCommitted     side effects executed exactly once, inside the write lock,
     *                        immediately after the new snapshot is published (e.g. resetting
     *                        rate-limit windows, syncing the health registry)
     */
    public UpdateResult atomicUpdate(GatewayConfig candidate, Consumer<GatewayConfig> onCommitted) {
        List<ValidationIssue> issues = validator.validate(candidate);
        lock.writeLock().lock();
        try {
            if (!issues.isEmpty()) {
                // Whole batch rejected; active config stays untouched.
                return new UpdateResult(false, current, issues);
            }
            GatewayConfig next = new GatewayConfig(current.version() + 1, candidate.routes());
            current = next; // single volatile publish: old or new, never a mixture
            if (onCommitted != null) {
                onCommitted.accept(next);
            }
            return new UpdateResult(true, next, List.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public UpdateResult atomicUpdate(GatewayConfig candidate) {
        return atomicUpdate(candidate, null);
    }

    /**
     * Bootstrap path: used for the built-in demo config. Still fully validated; returns
     * the result so the caller can log loudly rather than start with broken rules.
     */
    public UpdateResult initFrom(GatewayConfig config, Supplier<List<ValidationIssue>> preCheck) {
        List<ValidationIssue> issues = preCheck != null ? preCheck.get() : List.of();
        if (issues.isEmpty()) {
            return atomicUpdate(config);
        }
        return new UpdateResult(false, current, issues);
    }

    /** All backends declared across active routes, by id (de-duplicated). */
    public Map<String, BackendInstance> activeBackends() {
        Map<String, BackendInstance> byId = new LinkedHashMap<>();
        for (RouteRule route : current.routes()) {
            for (BackendInstance backend : route.backends()) {
                byId.putIfAbsent(backend.id(), backend);
            }
        }
        return Map.copyOf(byId);
    }
}
