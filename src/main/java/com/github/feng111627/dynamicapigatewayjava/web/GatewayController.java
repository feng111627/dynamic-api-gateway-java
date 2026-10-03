package com.github.feng111627.dynamicapigatewayjava.web;

import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayRequest;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayResponse;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关入口：承接各种 HTTP 方法（读取/写入/更新/删除等），
 * 把方法、请求头、查询参数、请求体原样交给后端；
 * 成功时后端的状态码/响应头/响应体如实回传，失败时返回带分类的 JSON 结果。
 */
@RestController
public class GatewayController {

    /** 由 Servlet 容器/HTTP 客户端重算的响应头，不原样回写。 */
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "connection", "content-length", "transfer-encoding", "keep-alive",
            "trailer", "upgrade");

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws Exception {
        // /admin 下的管理接口由 AdminController 精确匹配优先处理，这里只兜底业务流量
        String path = request.getRequestURI();
        if (path.startsWith("/admin/") || "/admin".equals(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "no such admin endpoint");
            return;
        }

        String callerId = request.getHeader("X-Caller-Id");
        if (callerId == null || callerId.isEmpty()) {
            callerId = "anonymous";
        }

        byte[] bodyBytes = request.getInputStream().readAllBytes();
        GatewayRequest gatewayRequest = new GatewayRequest(
                path,
                request.getMethod(),
                collectHeaders(request),
                request.getQueryString(),
                bodyBytes.length == 0 ? null : new String(bodyBytes, StandardCharsets.UTF_8));

        GatewayResponse resp = gatewayService.handle(gatewayRequest, callerId);
        writeResponse(response, resp);
    }

    private static Map<String, List<String>> collectHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new ArrayList<>();
            var vals = request.getHeaders(name);
            while (vals.hasMoreElements()) {
                values.add(vals.nextElement());
            }
            headers.put(name, values);
        }
        return headers;
    }

    private static void writeResponse(HttpServletResponse response, GatewayResponse resp) throws Exception {
        if (resp.errorKind() == GatewayResponse.ErrorKind.NONE) {
            // 成功：状态码/响应头/响应体原样回传
            response.setStatus(resp.status());
            resp.headers().forEach((name, values) -> {
                if (SKIP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    return;
                }
                for (String v : values) {
                    response.addHeader(name, v);
                }
            });
            byte[] out = resp.body() == null ? new byte[0] : resp.body().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = response.getOutputStream()) {
                os.write(out);
            }
            return;
        }

        // 错误：分类 JSON，并在响应头里带上错误类别，便于与限流/无路由/后端全挂区分
        response.setStatus(resp.status());
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        response.setHeader("X-Gateway-Error-Kind", resp.errorKind().name());
        String detail = resp.errorDetail() == null ? "" : resp.errorDetail();
        String json = "{\"errorKind\":\"" + resp.errorKind().name() + "\",\"error\":\""
                + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        try (OutputStream os = response.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }
}
