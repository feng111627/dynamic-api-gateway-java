package com.github.feng111627.dynamicapigatewayjava.balance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按实例的并发容量保护。
 *
 *  - 每个实例一个许可数 = 该实例当前配置的 maxConcurrency（0 表示不限并发）；
 *  - 到达上限时请求在锁/条件变量上排队等待（FIFO 公平），不超发、不静默丢弃；
 *  - 在 acquireTimeoutMillis 内等到空位则放行；超时仍没有空位则明确拒绝，
 *    由上层返回可区分的 BACKEND_OVERLOADED；
 *  - 配置随请求动态读取，因此热更新调大/调小上限不需要重建限流器；
 *  - 只做容量计数，不感知健康状态（健康由 HealthRegistry 负责）。
 */
public class InstanceConcurrencyLimiter {

    public enum AcquireOutcome {
        ACQUIRED,
        TIMEOUT,
        INTERRUPTED
    }

    public record AcquireResult(AcquireOutcome outcome, boolean acquired) {
        static AcquireResult ok() {
            return new AcquireResult(AcquireOutcome.ACQUIRED, true);
        }

        static AcquireResult timeout() {
            return new AcquireResult(AcquireOutcome.TIMEOUT, false);
        }

        static AcquireResult interrupted() {
            return new AcquireResult(AcquireOutcome.INTERRUPTED, false);
        }
    }

    private static final class Gate {
        final ReentrantLock lock = new ReentrantLock(true); // 公平：先排队者先得
        final Condition notFull = lock.newCondition();
        int active = 0;
    }

    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();

    private Gate gate(String backendId) {
        return gates.computeIfAbsent(backendId, k -> new Gate());
    }

    /**
     * 尝试占用一个并发名额。
     *
     * @param maxConcurrency 该实例当前并发上限（0 表示不限）
     * @param timeoutMillis  排队等待上限（<=0 表示不等待，立即判定）
     */
    public AcquireResult acquire(String backendId, int maxConcurrency, long timeoutMillis) {
        if (maxConcurrency <= 0) {
            return AcquireResult.ok(); // 不限并发
        }
        Gate g = gate(backendId);
        g.lock.lock();
        try {
            if (g.active < maxConcurrency) {
                g.active++;
                return AcquireResult.ok();
            }
            if (timeoutMillis <= 0) {
                return AcquireResult.timeout();
            }
            long nanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (g.active >= maxConcurrency) {
                if (nanos <= 0L) {
                    return AcquireResult.timeout();
                }
                nanos = g.notFull.awaitNanos(nanos);
            }
            g.active++;
            return AcquireResult.ok();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AcquireResult.interrupted();
        } finally {
            g.lock.unlock();
        }
    }

    /** 归还一个名额并唤醒一个排队者（正在处理的请求结束后调用，不会打断别人）。 */
    public void release(String backendId, int maxConcurrency) {
        Gate g = gate(backendId);
        g.lock.lock();
        try {
            if (g.active > 0) {
                g.active--;
            }
            if (maxConcurrency <= 0 || g.active < maxConcurrency) {
                g.notFull.signal();
            }
        } finally {
            g.lock.unlock();
        }
    }

    public int activeCount(String backendId) {
        Gate g = gates.get(backendId);
        if (g == null) {
            return 0;
        }
        g.lock.lock();
        try {
            return g.active;
        } finally {
            g.lock.unlock();
        }
    }
}
