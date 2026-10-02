package com.github.feng111627.dynamicapigatewayjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.dynamicapigatewayjava.config.GatewayProperties;
import com.github.feng111627.dynamicapigatewayjava.core.DispatchOutcome;
import com.github.feng111627.dynamicapigatewayjava.core.GatewayDispatcher;
import com.github.feng111627.dynamicapigatewayjava.forward.ForwardRequest;
import com.github.feng111627.dynamicapigatewayjava.ratelimit.RateLimitDecision;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single catch-all proxy entry point. Admin paths are served by their own controller and
 * take mapping precedence; everything else becomes one gateway dispatch attempt.
 */
@RestController
public class GatewayController {

    private final GatewayDispatcher dispatcher;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public GatewayController(GatewayDispatcher dispatcher, GatewayProperties properties,
                             ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) throws Exception {
        ForwardRequest forwardRequest = new ForwardRequest(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                collectHeaders(request),
                body == null ? new byte[0] : body);

        String callerId = request.getHeader(properties.getCallerHeader());
        DispatchOutcome outcome = dispatcher.dispatch(forwardRequest, callerId, System.currentTimeMillis());

        return switch (outcome.status()) {
            case "UPSTREAM_RESPONSE" -> upstreamResponse(outcome);
            case "RATE_LIMITED" -> rateLimited(outcome);
            default -> errorJson(outcome);
        };
    }

    private ResponseEntity<byte[]> upstreamResponse(DispatchOutcome outcome) {
        HttpHeaders headers = new HttpHeaders();
        if (outcome.upstreamResponse() != null && outcome.upstreamResponse().headers() != null) {
            outcome.upstreamResponse().headers().forEach((name, values) ->
                    headers.addAll(name, new ArrayList<>(values)));
        }
        addGatewayHeaders(headers, outcome);
        byte[] body = outcome.upstreamResponse() == null || outcome.upstreamResponse().body() == null
                ? new byte[0] : outcome.upstreamResponse().body();
        return ResponseEntity.status(outcome.httpStatus()).headers(headers).body(body);
    }

    private ResponseEntity<byte[]> rateLimited(DispatchOutcome outcome) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        addGatewayHeaders(headers, outcome);
        RateLimitDecision decision = outcome.rateLimitDecision();
        if (decision != null) {
            headers.add("X-RateLimit-Scope", decision.scope().name());
            headers.add("X-RateLimit-Route", decision.routeId());
            if (decision.callerId() != null) {
                headers.add("X-RateLimit-Caller", decision.callerId());
            }
            headers.add("X-RateLimit-Limit", String.valueOf(decision.limit()));
            headers.add("X-RateLimit-Window-Seconds", String.valueOf(decision.windowSeconds()));
            headers.add("X-RateLimit-Used", String.valueOf(decision.used()));
            long retryAfterMs = decision.windowStartMs() + decision.windowSeconds() * 1000L
                    - System.currentTimeMillis();
            headers.add("Retry-After", String.valueOf(Math.max(1, retryAfterMs / 1000)));
        }
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return ResponseEntity.status(429).headers(headers).body(toJson(outcome));
    }

    private ResponseEntity<byte[]> errorJson(DispatchOutcome outcome) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        addGatewayHeaders(headers, outcome);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return ResponseEntity.status(outcome.httpStatus()).headers(headers).body(toJson(outcome));
    }

    private void addGatewayHeaders(HttpHeaders headers, DispatchOutcome outcome) {
        if (outcome.routeId() != null) {
            headers.add("X-Gateway-Route", outcome.routeId());
        }
        if (outcome.instanceId() != null) {
            headers.add("X-Gateway-Instance", outcome.instanceId());
        }
        if (!outcome.attemptedInstances().isEmpty()) {
            headers.add("X-Gateway-Attempted", String.join(",", outcome.attemptedInstances()));
        }
        headers.add("X-Gateway-Result", outcome.status());
    }

    private byte[] toJson(DispatchOutcome outcome) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", outcome.status());
        payload.put("code", outcome.code());
        payload.put("message", outcome.message());
        payload.put("routeId", outcome.routeId());
        payload.put("instanceId", outcome.instanceId());
        payload.put("attemptedInstances", outcome.attemptedInstances());
        RateLimitDecision decision = outcome.rateLimitDecision();
        if (decision != null) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("scope", decision.scope().name());
            rule.put("routeId", decision.routeId());
            rule.put("callerId", decision.callerId());
            rule.put("limit", decision.limit());
            rule.put("windowSeconds", decision.windowSeconds());
            rule.put("used", decision.used());
            rule.put("windowStartMs", decision.windowStartMs());
            payload.put("rateLimitRule", rule);
        }
        return objectMapper.writeValueAsBytes(payload);
    }

    private Map<String, List<String>> collectHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new ArrayList<>();
            Enumeration<String> headerValues = request.getHeaders(name);
            while (headerValues.hasMoreElements()) {
                values.add(headerValues.nextElement());
            }
            headers.put(name, values);
        }
        return headers;
    }
}
