package com.github.feng111627.dynamicapigatewayjava.limit;

/** 限流判定结果：被拒绝时携带命中规则信息，便于与其他错误区分。 */
public record RateLimitDecision(boolean allowed, String ruleId, long remaining, long windowMillis) {

    public static RateLimitDecision allowed(long remaining, long windowMillis) {
        return new RateLimitDecision(true, null, remaining, windowMillis);
    }

    public static RateLimitDecision rejected(String ruleId, long windowMillis) {
        return new RateLimitDecision(false, ruleId, 0, windowMillis);
    }
}
