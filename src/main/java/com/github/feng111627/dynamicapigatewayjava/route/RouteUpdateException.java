package com.github.feng111627.dynamicapigatewayjava.route;

import java.util.List;

/** 路由批量更新被拒绝时抛出，调用方可通过 failures 区分具体是哪类问题。 */
public class RouteUpdateException extends RuntimeException {

    public enum ErrorType {
        EMPTY_BATCH,
        DUPLICATE_ROUTE_ID,
        INVALID_PATH_PREFIX,
        EMPTY_BACKENDS,
        DUPLICATE_BACKEND_ID
    }

    public record Failure(ErrorType type, String routeId, String message) {
    }

    private final List<Failure> failures;

    public RouteUpdateException(List<Failure> failures) {
        super("route update rejected: " + failures);
        this.failures = List.copyOf(failures);
    }

    public List<Failure> getFailures() {
        return failures;
    }
}
