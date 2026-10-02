package com.github.feng111627.dynamicapigatewayjava.route;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 不可变路由表快照。构造后即冻结，热更新时整体替换引用，
 * 保证并发读者要么看到完整旧表、要么看到完整新表，绝不出现"改了一半"的中间态。
 */
public class RouteTable {

    /** 按前缀长度降序排列，保证最长前缀优先匹配。 */
    private final List<Route> routes;

    public RouteTable(List<Route> routes) {
        this.routes = routes.stream()
                .sorted(Comparator.comparingInt((Route r) -> r.pathPrefix().length()).reversed())
                .toList();
    }

    /** 最长前缀匹配。 */
    public Optional<Route> match(String path) {
        if (path == null) {
            return Optional.empty();
        }
        return routes.stream()
                .filter(r -> path.startsWith(r.pathPrefix()))
                .findFirst();
    }

    public List<Route> routes() {
        return routes;
    }
}
