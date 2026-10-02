package com.github.feng111627.dynamicapigatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String callerHeader = "X-Caller-Id";
    private long healthProbeIntervalMs = 2000;
    private int healthyThreshold = 1;
    private int unhealthyThreshold = 1;
    private long stickyTtlMs = 60_000;
    private long stickyReattachBackoffMs = 1000;
    private int forwardTimeoutMs = 5000;
    private boolean retryGetOn5xx = true;
    private int demoRouteLimit = 100000;
    private int demoCallerLimit = 100000;
    private int demoWindowSeconds = 10;

    public String getCallerHeader() { return callerHeader; }
    public void setCallerHeader(String callerHeader) { this.callerHeader = callerHeader; }
    public long getHealthProbeIntervalMs() { return healthProbeIntervalMs; }
    public void setHealthProbeIntervalMs(long healthProbeIntervalMs) { this.healthProbeIntervalMs = healthProbeIntervalMs; }
    public int getHealthyThreshold() { return healthyThreshold; }
    public void setHealthyThreshold(int healthyThreshold) { this.healthyThreshold = healthyThreshold; }
    public int getUnhealthyThreshold() { return unhealthyThreshold; }
    public void setUnhealthyThreshold(int unhealthyThreshold) { this.unhealthyThreshold = unhealthyThreshold; }
    public long getStickyTtlMs() { return stickyTtlMs; }
    public void setStickyTtlMs(long stickyTtlMs) { this.stickyTtlMs = stickyTtlMs; }
    public long getStickyReattachBackoffMs() { return stickyReattachBackoffMs; }
    public void setStickyReattachBackoffMs(long stickyReattachBackoffMs) { this.stickyReattachBackoffMs = stickyReattachBackoffMs; }
    public int getForwardTimeoutMs() { return forwardTimeoutMs; }
    public void setForwardTimeoutMs(int forwardTimeoutMs) { this.forwardTimeoutMs = forwardTimeoutMs; }
    public boolean isRetryGetOn5xx() { return retryGetOn5xx; }
    public void setRetryGetOn5xx(boolean retryGetOn5xx) { this.retryGetOn5xx = retryGetOn5xx; }
    public int getDemoRouteLimit() { return demoRouteLimit; }
    public void setDemoRouteLimit(int demoRouteLimit) { this.demoRouteLimit = demoRouteLimit; }
    public int getDemoCallerLimit() { return demoCallerLimit; }
    public void setDemoCallerLimit(int demoCallerLimit) { this.demoCallerLimit = demoCallerLimit; }
    public int getDemoWindowSeconds() { return demoWindowSeconds; }
    public void setDemoWindowSeconds(int demoWindowSeconds) { this.demoWindowSeconds = demoWindowSeconds; }
}
