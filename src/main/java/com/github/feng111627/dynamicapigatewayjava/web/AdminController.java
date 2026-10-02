package com.github.feng111627.dynamicapigatewayjava.web;

import com.github.feng111627.dynamicapigatewayjava.route.Route;
import com.github.feng111627.dynamicapigatewayjava.route.RouteRegistry;
import com.github.feng111627.dynamicapigatewayjava.route.RouteUpdateException;
import com.github.feng111627.dynamicapigatewayjava.sticky.BackendInstance;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 管理端：运行期热更新路由规则，整批原子生效。 */
@RestController
public class AdminController {

    private final RouteRegistry routeRegistry;

    public AdminController(RouteRegistry routeRegistry) {
        this.routeRegistry = routeRegistry;
    }

    public record BackendDto(String id, String host, int port) {
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
                                .map(b -> new BackendInstance(b.id(), b.host(), b.port()))
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
}
