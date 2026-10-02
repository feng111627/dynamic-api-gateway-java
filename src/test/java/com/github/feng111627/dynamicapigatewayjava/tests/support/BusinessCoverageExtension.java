package com.github.feng111627.dynamicapigatewayjava.tests.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试覆盖日志扩展：每个用例执行后打印 [业务域] 用例名 => 通过/失败，
 * 类级别汇总该业务域的用例总数与通过数，让测试日志能直接看出覆盖了哪些业务。
 */
public class BusinessCoverageExtension implements TestWatcher, AfterAllCallback {

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Business {
        String value();
    }

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-COVERAGE");
    private static final java.util.concurrent.ConcurrentHashMap<String, int[]> STATS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void testSuccessful(ExtensionContext context) {
        report(context, "PASS");
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        report(context, "FAIL(" + cause.getMessage() + ")");
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        report(context, "SKIP");
    }

    private void report(ExtensionContext context, String outcome) {
        String business = context.getTestMethod()
                .map(m -> m.getAnnotation(Business.class))
                .map(Business::value)
                .orElse("未分类");
        int[] counter = STATS.computeIfAbsent(business, k -> new int[2]);
        synchronized (counter) {
            counter[0]++;
            if ("PASS".equals(outcome)) {
                counter[1]++;
            }
        }
        log.info("[业务域:{}] 用例:{} => {}", business, context.getDisplayName(), outcome);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        STATS.forEach((business, counter) -> {
            synchronized (counter) {
                log.info("[业务域汇总] {} : 用例数={} 通过={}", business, counter[0], counter[1]);
            }
        });
    }
}
