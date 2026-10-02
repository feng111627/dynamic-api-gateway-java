package com.github.feng111627.dynamicapigatewayjava.sticky;

/** 一条粘性会话：调用方 -> 后端实例，带过期时间。 */
public record StickySession(String callerId, String backendId, long expireAtMillis) {

    public boolean expired(long nowMillis) {
        return nowMillis >= expireAtMillis;
    }
}
