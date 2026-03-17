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

public record RolePackageUpsertCommand(
        String roleCode,
        String displayName,
        String persona,
        String version,
        String status,
        List<RoleScenarioInput> scenarios,
        List<RoleToolScopeInput> toolScopes,
        List<RoleProactiveTaskInput> proactiveTasks,
        List<RoleKpiMetricInput> kpiMetrics) {

    public RolePackageUpsertCommand {
        roleCode = normalize(roleCode);
        displayName = normalize(displayName);
        persona = normalize(persona);
        version = normalize(version);
        status = normalizeLower(status);
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        toolScopes = toolScopes == null ? List.of() : List.copyOf(toolScopes);
        proactiveTasks = proactiveTasks == null ? List.of() : List.copyOf(proactiveTasks);
        kpiMetrics = kpiMetrics == null ? List.of() : List.copyOf(kpiMetrics);
    }

    public RolePackageUpsertCommand withRoleCode(String newRoleCode) {
        return new RolePackageUpsertCommand(
                newRoleCode,
                displayName,
                persona,
                version,
                status,
                scenarios,
                toolScopes,
                proactiveTasks,
                kpiMetrics);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String normalizeLower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
    }

    public record RoleScenarioInput(
            String scenarioCode,
            String displayName,
            String description,
            Map<String, Object> routingHints) {
    }

    public record RoleToolScopeInput(
            String scenarioCode,
            String toolCode,
            String scopeMode) {
    }

    public record RoleProactiveTaskInput(
            String taskCode,
            String cronExpr,
            String artifactCode,
            String scenarioCode,
            Map<String, Object> taskPayload,
            String status) {
    }

    public record RoleKpiMetricInput(
            String metricCode,
            String displayName,
            String targetValue,
            Map<String, Object> metricDefinition) {
    }
}
