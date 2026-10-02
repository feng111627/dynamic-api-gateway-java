package com.github.feng111627.dynamicapigatewayjava.limit;

/**
 * 限流规则：按 (路由, 调用方) 两个维度匹配。
 * 维度取值语义：
 *   "*"    —— 通配：匹配任意值，且每个具体值独立计数（如 callerId="*" 表示每个调用方各自限额）；
 *   "@all" —— 共享：匹配任意值，所有值共用一个计数桶（如 callerId="@all" 表示全体调用方共享限额）；
 *   其他   —— 精确匹配该值。
 * 窗口为固定窗口：以 windowMillis 对齐的自然时间片。
 */
public record RateLimitRule(String ruleId, String routeId, String callerId,
                            long maxRequests, long windowMillis) {

    public static final String ANY = "*";
    public static final String SHARED = "@all";

    public boolean matches(String route, String caller) {
        return dimMatches(routeId, route) && dimMatches(callerId, caller);
    }

    private static boolean dimMatches(String ruleDim, String actual) {
        return ANY.equals(ruleDim) || SHARED.equals(ruleDim) || ruleDim.equals(actual);
    }
}
