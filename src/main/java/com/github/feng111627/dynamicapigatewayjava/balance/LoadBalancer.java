package com.github.feng111627.dynamicapigatewayjava.balance;

import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.util.List;
import java.util.Optional;

/** 负载均衡策略：在一组候选实例中选一个。 */
public interface LoadBalancer {

    /**
     * 从候选实例中选一个。
     * @param routeId   路由 id（策略内部状态按路由隔离）
     * @param eligible  候选实例（健康、本次请求尚未尝试过），顺序稳定
     */
    Optional<BackendInstance> select(String routeId, List<BackendInstance> eligible);
}
