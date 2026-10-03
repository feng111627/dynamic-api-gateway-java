package com.github.feng111627.dynamicapigatewayjava.sticky;

/** 后端实例描述（骨架，后续补充权重与并发上限语义）。 */
public record BackendInstance(String id, String host, int port, int weight, int maxConcurrency) {

    /** 兼容旧用法：默认权重 1、不设并发上限（0 表示不限）。 */
    public BackendInstance(String id, String host, int port) {
        this(id, host, port, 1, 0);
    }

    public String baseUrl() {
        return "http://" + host + ":" + port;
    }
}
