package com.github.feng111627.dynamicapigatewayjava.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 路由注册中心。
 * 热更新语义：先对整批规则做完整校验，全部合法才一次性原子替换路由表；
 * 任何一条非法都整体拒绝（抛 RouteUpdateException，携带分类后的失败明细），旧表保持不变。
 */
public class RouteRegistry {

    private final AtomicReference<RouteTable> tableRef;

    public RouteRegistry(List<Route> initialRoutes) {
        List<RouteUpdateException.Failure> failures = validate(initialRoutes);
        if (!failures.isEmpty()) {
            throw new RouteUpdateException(failures);
        }
        this.tableRef = new AtomicReference<>(new RouteTable(initialRoutes));
    }

    /**
     * 批量热更新：整批校验 -> 构建新表 -> 单次原子替换。
     * 校验失败时不触碰旧表，调用方可从异常中区分每类问题。
     */
    public void updateRoutes(List<Route> newRoutes) {
        List<RouteUpdateException.Failure> failures = validate(newRoutes);
        if (!failures.isEmpty()) {
            throw new RouteUpdateException(failures);
        }
        // 校验通过后才构建并替换：对读者而言切换是瞬时的、整批的。
        tableRef.set(new RouteTable(newRoutes));
    }

    public Optional<Route> match(String path) {
        return tableRef.get().match(path);
    }

    public RouteTable snapshot() {
        return tableRef.get();
    }

    private static List<RouteUpdateException.Failure> validate(List<Route> routes) {
        List<RouteUpdateException.Failure> failures = new ArrayList<>();
        if (routes == null || routes.isEmpty()) {
            failures.add(new RouteUpdateException.Failure(
                    RouteUpdateException.ErrorType.EMPTY_BATCH, null, "route batch must not be empty"));
            return failures;
        }
        Set<String> routeIds = new HashSet<>();
        for (Route r : routes) {
            String rid = r == null ? null : r.id();
            if (r == null || rid == null || rid.isBlank()) {
                failures.add(new RouteUpdateException.Failure(
                        RouteUpdateException.ErrorType.DUPLICATE_ROUTE_ID, null, "route id must not be blank"));
                continue;
            }
            if (!routeIds.add(rid)) {
                failures.add(new RouteUpdateException.Failure(
                        RouteUpdateException.ErrorType.DUPLICATE_ROUTE_ID, rid, "duplicate route id: " + rid));
            }
            String prefix = r.pathPrefix();
            if (prefix == null || !prefix.startsWith("/") || prefix.length() < 2 || prefix.contains("..")) {
                failures.add(new RouteUpdateException.Failure(
                        RouteUpdateException.ErrorType.INVALID_PATH_PREFIX, rid,
                        "invalid path prefix: " + prefix));
            }
            if (r.backends() == null || r.backends().isEmpty()) {
                failures.add(new RouteUpdateException.Failure(
                        RouteUpdateException.ErrorType.EMPTY_BACKENDS, rid,
                        "route must have at least one backend"));
            } else {
                Set<String> backendIds = new HashSet<>();
                for (var b : r.backends()) {
                    if (b == null || b.id() == null || b.id().isBlank()
                            || b.host() == null || b.host().isBlank()
                            || b.port() <= 0 || b.port() > 65535) {
                        failures.add(new RouteUpdateException.Failure(
                                RouteUpdateException.ErrorType.EMPTY_BACKENDS, rid,
                                "backend instance is malformed: " + b));
                    } else if (!backendIds.add(b.id())) {
                        failures.add(new RouteUpdateException.Failure(
                                RouteUpdateException.ErrorType.DUPLICATE_BACKEND_ID, rid,
                                "duplicate backend id in route: " + b.id()));
                    }
                }
            }
        }
        return failures;
    }
}
