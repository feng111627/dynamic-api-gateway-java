package com.github.feng111627.dynamicapigatewayjava.sticky;

/**
 * 后端实例描述。
 *
 * weight：负载均衡权重（>=1），新会话按权重分散到各健康实例。
 * maxConcurrency：单实例并发上限，<=0 表示不限；到达上限后请求不会被超发，
 * 由网关给出可区分的拒绝结果（BACKEND_AT_CAPACITY）。
 */
public record BackendInstance(String id, String host, int port, int weight, int maxConcurrency) {

    /** 兼容构造：默认权重 1、不限并发。 */
    public BackendInstance(String id, String host, int port) {
        this(id, host, port, 1, 0);
    }

    public BackendInstance {
        if (weight < 1) {
            weight = 1;
        }
    }

    public String baseUrl() {
        return "http://" + host + ":" + port;
    }

    /** 是否不限并发。 */
    public boolean unlimitedConcurrency() {
        return maxConcurrency <= 0;
    }
}
