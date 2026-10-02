package com.github.feng111627.dynamicapigatewayjava.sticky;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例健康注册表。默认健康；转发失败时标记不健康，恢复探测成功后标记健康。
 */
public class HealthRegistry {

    private final Set<String> unhealthy = ConcurrentHashMap.newKeySet();

    public void markHealthy(String backendId) {
        unhealthy.remove(backendId);
    }

    public void markUnhealthy(String backendId) {
        unhealthy.add(backendId);
    }

    public boolean isHealthy(String backendId) {
        return !unhealthy.contains(backendId);
    }
}
