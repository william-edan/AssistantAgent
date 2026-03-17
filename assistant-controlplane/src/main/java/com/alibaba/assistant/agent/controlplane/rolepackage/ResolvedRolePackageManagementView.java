/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.assistant.agent.controlplane.rolepackage;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ResolvedRolePackageManagementView(
        Long id,
        String spaceCode,
        String environment,
        String agentAppCode,
        String roleCode,
        String displayName,
        String persona,
        String version,
        String status,
        List<RoleScenarioView> scenarios,
        List<RoleToolScopeView> toolScopes,
        List<RoleProactiveTaskView> proactiveTasks,
        List<RoleKpiMetricView> kpiMetrics) {

    public ResolvedRolePackageManagementView {
        status = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        toolScopes = toolScopes == null ? List.of() : List.copyOf(toolScopes);
        proactiveTasks = proactiveTasks == null ? List.of() : List.copyOf(proactiveTasks);
        kpiMetrics = kpiMetrics == null ? List.of() : List.copyOf(kpiMetrics);
    }

    public record RoleScenarioView(
            String scenarioCode,
            String displayName,
            String description,
            Map<String, Object> routingHints) {
    }

    public record RoleToolScopeView(
            String scenarioCode,
            String toolCode,
            String scopeMode) {
    }

    public record RoleProactiveTaskView(
            String taskCode,
            String cronExpr,
            String artifactCode,
            String scenarioCode,
            Map<String, Object> taskPayload,
            String status) {
    }

    public record RoleKpiMetricView(
            String metricCode,
            String displayName,
            String targetValue,
            Map<String, Object> metricDefinition) {
    }
}
