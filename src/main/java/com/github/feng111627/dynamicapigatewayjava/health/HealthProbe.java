package com.github.feng111627.dynamicapigatewayjava.health;

import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;

/** 健康探测：返回 true 表示实例存活可恢复（骨架）。 */
public interface HealthProbe {

    boolean isAlive(BackendInstance backend);
}
