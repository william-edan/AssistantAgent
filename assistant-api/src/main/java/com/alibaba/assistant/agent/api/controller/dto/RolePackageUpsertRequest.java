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
package com.alibaba.assistant.agent.api.controller.dto;

import com.alibaba.assistant.agent.controlplane.rolepackage.RolePackageUpsertCommand;

import java.util.List;
import java.util.Map;

/**
 * Request payload for role-package create/update operations.
 */
public record RolePackageUpsertRequest(
        String displayName,
        String persona,
        String version,
        String status,
        List<RoleScenarioInput> scenarios,
        List<RoleToolScopeInput> toolScopes,
        List<RoleProactiveTaskInput> proactiveTasks,
        List<RoleKpiMetricInput> kpiMetrics) {

    public RolePackageUpsertCommand toCommand(String roleCode) {
        return new RolePackageUpsertCommand(
                roleCode,
                displayName,
                persona,
                version,
                status,
                scenarios == null ? List.of() : scenarios.stream().map(RoleScenarioInput::toCommand).toList(),
                toolScopes == null ? List.of() : toolScopes.stream().map(RoleToolScopeInput::toCommand).toList(),
                proactiveTasks == null ? List.of() : proactiveTasks.stream().map(RoleProactiveTaskInput::toCommand).toList(),
                kpiMetrics == null ? List.of() : kpiMetrics.stream().map(RoleKpiMetricInput::toCommand).toList());
    }

    public record RoleScenarioInput(
            String scenarioCode,
            String displayName,
            String description,
            Map<String, Object> routingHints) {

        public RolePackageUpsertCommand.RoleScenarioInput toCommand() {
            return new RolePackageUpsertCommand.RoleScenarioInput(scenarioCode, displayName, description, routingHints);
        }
    }

    public record RoleToolScopeInput(
            String scenarioCode,
            String toolCode,
            String scopeMode) {

        public RolePackageUpsertCommand.RoleToolScopeInput toCommand() {
            return new RolePackageUpsertCommand.RoleToolScopeInput(scenarioCode, toolCode, scopeMode);
        }
    }

    public record RoleProactiveTaskInput(
            String taskCode,
            String cronExpr,
            String artifactCode,
            String scenarioCode,
            Map<String, Object> taskPayload,
            String status) {

        public RolePackageUpsertCommand.RoleProactiveTaskInput toCommand() {
            return new RolePackageUpsertCommand.RoleProactiveTaskInput(
                    taskCode,
                    cronExpr,
                    artifactCode,
                    scenarioCode,
                    taskPayload,
                    status);
        }
    }

    public record RoleKpiMetricInput(
            String metricCode,
            String displayName,
            String targetValue,
            Map<String, Object> metricDefinition) {

        public RolePackageUpsertCommand.RoleKpiMetricInput toCommand() {
            return new RolePackageUpsertCommand.RoleKpiMetricInput(metricCode, displayName, targetValue, metricDefinition);
        }
    }
}
