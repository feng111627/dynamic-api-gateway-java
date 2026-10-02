package com.github.feng111627.dynamicapigatewayjava.sticky;

/** 后端实例描述。 */
public record BackendInstance(String id, String host, int port) {

    public String baseUrl() {
        return "http://" + host + ":" + port;
    }
}
