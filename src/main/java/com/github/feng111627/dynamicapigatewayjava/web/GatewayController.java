package com.github.feng111627.dynamicapigatewayjava.web;

import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayResponse;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 网关入口：接收业务请求并交给 GatewayService 转发。 */
@RestController
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/**")
    public ResponseEntity<Map<String, Object>> proxy(HttpServletRequest request,
                                                     @RequestBody(required = false) String body,
                                                     @RequestHeader(value = "X-Caller-Id", defaultValue = "anonymous") String callerId) {
        String path = request.getRequestURI();
        GatewayResponse resp = gatewayService.handle(path, callerId, body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("errorKind", resp.errorKind().name());
        if (resp.backendId() != null) {
            out.put("backendId", resp.backendId());
        }
        if (resp.body() != null) {
            out.put("backendBody", resp.body());
        }
        if (resp.errorDetail() != null) {
            out.put("error", resp.errorDetail());
        }
        return ResponseEntity.status(resp.status()).body(out);
    }
}
