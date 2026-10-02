package com.github.feng111627.dynamicapigatewayjava.limit;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 固定窗口限流器，按 (规则, 路由, 调用方) 维度计数。
 *
 * 并发安全：计数用 ConcurrentHashMap.compute 内的原子更新，配额不会被超发。
 * 窗口切换：计数槽位以窗口序号(windowIndex)为键的一部分，新窗口使用全新槽位，
 * 旧槽位惰性清理——切换瞬间不会出现重复计数（旧窗口请求不会计入新窗口），
 * 也不会漏计（每个请求只落入一个确定的窗口槽位）。
 */
public class RateLimiter {

    /** 一个窗口槽位：windowIndex 标识所属窗口，count 为窗口内已用配额。 */
    private record Slot(long windowIndex, long windowMillis, AtomicLong count) {
    }

    private final List<RateLimitRule> rules;
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public RateLimiter(List<RateLimitRule> rules) {
        this(rules, System::currentTimeMillis);
    }

    /** 可注入时钟，便于测试窗口切换。 */
    public RateLimiter(List<RateLimitRule> rules, LongSupplier clock) {
        this.rules = List.copyOf(rules);
        this.clock = clock;
    }

    /**
     * 对 (routeId, callerId) 的一次请求做限流判定。
     * 命中多条规则时全部检查，任一超限即拒绝，返回首先拒绝的那条规则。
     */
    public RateLimitDecision check(String routeId, String callerId) {
        long now = clock.getAsLong();
        long minRemaining = Long.MAX_VALUE;
        long minWindow = 0;
        for (RateLimitRule rule : rules) {
            if (!rule.matches(routeId, callerId)) {
                continue;
            }
            long windowIndex = now / rule.windowMillis();
            // 计数键按规则维度语义取值：
            //   "*" 维度 -> 用请求的具体值（每个值独立配额）；
            //   "@all"/精确值 -> 用规则值（共享或固定桶）。
            String key = rule.ruleId() + "|"
                    + dimKey(rule.routeId(), routeId) + "|"
                    + dimKey(rule.callerId(), callerId);
            // compute 保证同一 key 的槽位获取与计数是原子的：
            // 并发打量时只有前 maxRequests 个请求能拿到配额。
            boolean[] allowed = new boolean[1];
            Slot slot = slots.compute(key, (k, old) -> {
                Slot s = (old == null || old.windowIndex() != windowIndex)
                        ? new Slot(windowIndex, rule.windowMillis(), new AtomicLong(0))
                        : old;
                long next = s.count().incrementAndGet();
                allowed[0] = next <= rule.maxRequests();
                return s;
            });
            if (!allowed[0]) {
                return RateLimitDecision.rejected(rule.ruleId(), rule.windowMillis());
            }
            long remaining = rule.maxRequests() - slot.count().get();
            if (remaining < minRemaining) {
                minRemaining = remaining;
                minWindow = rule.windowMillis();
            }
        }
        if (minRemaining == Long.MAX_VALUE) {
            return RateLimitDecision.allowed(-1, 0); // 无规则命中，不限流
        }
        return RateLimitDecision.allowed(minRemaining, minWindow);
    }

    private static String dimKey(String ruleDim, String actual) {
        return RateLimitRule.ANY.equals(ruleDim) ? actual : ruleDim;
    }

    /** 清理已过期窗口的槽位（可周期性调用，非必须；不影响正确性）。 */
    public void evictStaleSlots() {
        long now = clock.getAsLong();
        slots.forEach((key, slot) -> {
            long currentWindow = now / slot.windowMillis();
            // 只清理已经不属于当前窗口的旧槽位，活跃槽位不受影响
            if (slot.windowIndex() < currentWindow) {
                slots.remove(key, slot);
            }
        });
    }
}
