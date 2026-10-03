package com.github.feng111627.dynamicapigatewayjava.web;

import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.route.RouteUpdateException;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import com.github.feng111627.dynamicapigatewayjava.sticky.HealthRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 管理端：运行期热更新路由规则（整批原子生效），并对外暴露实例健康状态。 */
@RestController
public class AdminController {

    private final RouteRegistry routeRegistry;
    private final HealthRegistry healthRegistry;

    public AdminController(RouteRegistry routeRegistry, HealthRegistry healthRegistry) {
        this.routeRegistry = routeRegistry;
        this.healthRegistry = healthRegistry;
    }

    /**
     * 后端描述：weight 缺省 1；maxConcurrency 缺省 0（不设并发上限）。
     */
    public record BackendDto(String id, String host, Integer port,
                             Integer weight, Integer maxConcurrency) {
        private int w() {
            return weight == null ? 1 : weight;
        }

        private int c() {
            return maxConcurrency == null ? 0 : maxConcurrency;
        }
    }

    public record RouteDto(String id, String pathPrefix, List<BackendDto> backends) {
    }

    public record UpdateRoutesRequest(List<RouteDto> routes) {
    }

    @PutMapping("/admin/routes")
    public ResponseEntity<Map<String, Object>> updateRoutes(@RequestBody UpdateRoutesRequest req) {
        List<Route> routes = req.routes() == null ? List.of() : req.routes().stream()
                .map(d -> new Route(d.id(), d.pathPrefix(),
                        d.backends() == null ? List.of() : d.backends().stream()
                                .map(b -> new BackendInstance(b.id(), b.host(),
                                        b.port() == null ? 0 : b.port(), b.w(), b.c()))
                                .toList()))
                .toList();
        try {
            routeRegistry.updateRoutes(routes);
        } catch (RouteUpdateException e) {
            // 整批被拒绝：返回分类后的失败明细，调用方可区分问题类型
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("updated", false);
            out.put("failures", e.getFailures().stream()
                    .map(f -> Map.of("type", f.type().name(),
                            "routeId", f.routeId() == null ? "" : f.routeId(),
                            "message", f.message()))
                    .toList());
            return ResponseEntity.badRequest().body(out);
        }
        return ResponseEntity.ok(Map.of("updated", true, "routeCount", routes.size()));
    }

    @GetMapping("/admin/routes")
    public List<Route> listRoutes() {
        return routeRegistry.snapshot().routes();
    }

    /**
     * 实例健康状态快照（外部可观察）：
     * 按路由列出每个后端的 HEALTHY/UNHEALTHY，并给出整体不健康实例集合。
     */
    @GetMapping("/admin/health")
    public Map<String, Object> health() {
        List<Map<String, Object>> routes = routeRegistry.snapshot().routes().stream()
                .map(route -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("routeId", route.id());
                    List<Map<String, Object>> backends = route.backends().stream()
                            .map(b -> Map.<String, Object>of(
                                    "id", b.id(),
                                    "weight", b.weight(),
                                    "maxConcurrency", b.maxConcurrency(),
                                    "state", healthRegistry.stateOf(b.id()).name()))
                            .toList();
                    r.put("backends", backends);
                    return r;
                })
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("routes", routes);
        out.put("unhealthy", healthRegistry.unhealthySnapshot());
        return out;
    }
}
