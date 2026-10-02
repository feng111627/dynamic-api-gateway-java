package com.github.feng111627.dynamicapigatewayjava.routing;

import com.github.feng111627.dynamicapigatewayjava.model.GatewayConfig;
import com.github.feng111627.dynamicapigatewayjava.model.ValidationIssue;

import java.util.List;

public record UpdateResult(boolean accepted, GatewayConfig activeConfig, List<ValidationIssue> issues) {
}
