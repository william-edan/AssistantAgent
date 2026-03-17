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

import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;

import java.util.List;
import java.util.Map;

/**
 * API envelope for managed role-package responses.
 */
public record RolePackageResponse(int code, String msg, RolePackageResponse.Data data) {

    public static RolePackageResponse ok(ResolvedRolePackageManagementView view) {
        return new RolePackageResponse(0, "", Data.from(view));
    }

    public record Data(
            String spaceCode,
            String environment,
            String agentAppCode,
            String roleCode,
            String displayName,
            String persona,
            String version,
            String status,
            List<RoleScenarioData> scenarios,
            List<RoleToolScopeData> toolScopes,
            List<RoleProactiveTaskData> proactiveTasks,
            List<RoleKpiMetricData> kpiMetrics) {

        public static Data from(ResolvedRolePackageManagementView view) {
            return new Data(
                    view.spaceCode(),
                    view.environment(),
                    view.agentAppCode(),
                    view.roleCode(),
                    view.displayName(),
                    view.persona(),
                    view.version(),
                    view.status(),
                    view.scenarios().stream().map(RoleScenarioData::from).toList(),
                    view.toolScopes().stream().map(RoleToolScopeData::from).toList(),
                    view.proactiveTasks().stream().map(RoleProactiveTaskData::from).toList(),
                    view.kpiMetrics().stream().map(RoleKpiMetricData::from).toList());
        }
    }

    public record RoleScenarioData(
            String scenarioCode,
            String displayName,
            String description,
            Map<String, Object> routingHints) {

        public static RoleScenarioData from(ResolvedRolePackageManagementView.RoleScenarioView view) {
            return new RoleScenarioData(view.scenarioCode(), view.displayName(), view.description(), view.routingHints());
        }
    }

    public record RoleToolScopeData(
            String scenarioCode,
            String toolCode,
            String scopeMode) {

        public static RoleToolScopeData from(ResolvedRolePackageManagementView.RoleToolScopeView view) {
            return new RoleToolScopeData(view.scenarioCode(), view.toolCode(), view.scopeMode());
        }
    }

    public record RoleProactiveTaskData(
            String taskCode,
            String cronExpr,
            String artifactCode,
            String scenarioCode,
            Map<String, Object> taskPayload,
            String status) {

        public static RoleProactiveTaskData from(ResolvedRolePackageManagementView.RoleProactiveTaskView view) {
            return new RoleProactiveTaskData(
                    view.taskCode(),
                    view.cronExpr(),
                    view.artifactCode(),
                    view.scenarioCode(),
                    view.taskPayload(),
                    view.status());
        }
    }

    public record RoleKpiMetricData(
            String metricCode,
            String displayName,
            String targetValue,
            Map<String, Object> metricDefinition) {

        public static RoleKpiMetricData from(ResolvedRolePackageManagementView.RoleKpiMetricView view) {
            return new RoleKpiMetricData(view.metricCode(), view.displayName(), view.targetValue(), view.metricDefinition());
        }
    }
}
