package com.github.feng111627.dynamicapigatewayjava.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在表单参数解析发生之前，把最原始的请求体一次性缓存下来，并让 getInputStream/getReader
 * 从缓存返回。这样即使 Spring 把 application/x-www-form-urlencoded 的 PUT/DELETE 也当作
 * 表单去解析（从而提前消费 body），网关仍能透传原始请求体。
 *
 * 同时关闭该 wrapper 的表单参数解析（getParameter 不再触发 body 读取），
 * 保证透传用的输入流就是调用方发来的原始字节。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RawBodyCachingFilter extends OncePerRequestFilter {

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private final Charset charset;

        CachedBodyRequest(HttpServletRequest request, byte[] body, Charset charset) {
            super(request);
            this.body = body;
            this.charset = charset;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        /** 不基于 body 解析表单参数，避免二次语义化；查询串参数仍可由 getParameter 返回。 */
        @Override
        public String getParameter(String name) {
            String qs = super.getQueryString();
            if (qs == null) {
                return null;
            }
            for (String pair : qs.split("&")) {
                int i = pair.indexOf('=');
                String k = i < 0 ? pair : pair.substring(0, i);
                if (k.equals(name)) {
                    return i < 0 ? "" : pair.substring(i + 1);
                }
            }
            return null;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 只缓存有请求体的方法；GET/HEAD/OPTIONS 通常无体
        String method = request.getMethod();
        boolean mayHaveBody = !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method));
        if (mayHaveBody) {
            Charset charset = resolveCharset(request);
            byte[] body = request.getInputStream().readAllBytes();
            filterChain.doFilter(new CachedBodyRequest(request, body, charset), response);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    private static Charset resolveCharset(HttpServletRequest request) {
        try {
            MediaType ct = MediaType.parseMediaType(request.getContentType());
            if (ct != null && ct.getCharset() != null) {
                return ct.getCharset();
            }
        } catch (Exception ignored) {
            // 非法/缺失 Content-Type 时退回 UTF-8
        }
        return StandardCharsets.UTF_8;
    }
}
