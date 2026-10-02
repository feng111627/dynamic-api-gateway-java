package com.github.feng111627.dynamicapigatewayjava.model;

public record BackendInstance(String id, String baseUrl) {
    public BackendInstance {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("backend instance id must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("backend instance baseUrl must not be blank");
        }
    }
}
