package com.github.feng111627.dynamicapigatewayjava.web;

import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayRequest;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayResponse;
import com.github.feng111627.dynamicapigatewayjava.gateway.GatewayService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关入口：承接各种常见请求语义（GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS 等），
 * 把方法、查询串、业务请求头、请求体原样交给 GatewayService 透传；
 * 成功时后端的状态码、响应头、响应体如实带回，错误时返回分类后的 JSON 错误。
 */
@RestController
public class GatewayController {

    /** 响应头里不由网关逐字转发的逐跳/长度类头。 */
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding", "content-length",
            "content-encoding", "trailer", "upgrade");

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @RequestMapping("/**")
    public ResponseEntity<?> proxy(HttpServletRequest request,
                                   @RequestHeader(value = "X-Caller-Id", defaultValue = "anonymous") String callerId)
            throws IOException {
        GatewayRequest req = new GatewayRequest(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                headersOf(request),
                callerId,
                rawBody(request));
        GatewayResponse resp = gatewayService.handle(req);

        if (resp.errorKind() == GatewayResponse.ErrorKind.NONE) {
            // 成功：状态码、响应头、响应体原样带回
            HttpHeaders out = new HttpHeaders();
            resp.headers().forEach((name, values) -> {
                if (!SKIP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    out.put(name, values);
                }
            });
            return ResponseEntity.status(resp.status())
                    .headers(out)
                    .body(resp.body());
        }
        // 失败：分类后的错误，调用方可区分
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("errorKind", resp.errorKind().name());
        if (resp.errorDetail() != null) {
            err.put("error", resp.errorDetail());
        }
        return ResponseEntity.status(resp.status()).body(err);
    }

    /**
     * 直接读原始请求体（不经 @RequestBody 的转换器链），
     * 避免 application/x-www-form-urlencoded 等类型被容器提前解析消费，
     * 保证任何内容类型的请求体都原样透传。
     */
    private static String rawBody(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readAllBytes();
        if (bytes.length == 0) {
            return null;
        }
        Charset charset = request.getCharacterEncoding() != null
                ? Charset.forName(request.getCharacterEncoding())
                : StandardCharsets.UTF_8;
        return new String(bytes, charset);
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
