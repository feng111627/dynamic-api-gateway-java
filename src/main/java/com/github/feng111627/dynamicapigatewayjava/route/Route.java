package com.github.feng111627.dynamicapigatewayjava.route;

import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.util.List;

/** 一条路由规则：路径前缀 -> 一组后端实例。 */
public record Route(String id, String pathPrefix, List<BackendInstance> backends) {
}
