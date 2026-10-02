package com.github.feng111627.dynamicapigatewayjava.health;

@FunctionalInterface
public interface BackendProber {
    boolean probe(String baseUrl);
}
