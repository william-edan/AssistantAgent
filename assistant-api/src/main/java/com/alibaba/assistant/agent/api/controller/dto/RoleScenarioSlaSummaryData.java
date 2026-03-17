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

import com.alibaba.assistant.agent.runtime.observability.RoleScenarioSlaSummary;

import java.time.LocalDateTime;

/**
 * API payload for role-scenario SLA summaries.
 */
public record RoleScenarioSlaSummaryData(
        Long spaceId,
        String spaceCode,
        String environment,
        String agentAppCode,
        String rolePackageCode,
        String scenarioCode,
        int totalRuns,
        int successfulRuns,
        int slaMetRuns,
        double slaMetRate,
        long averageDurationMs,
        double proactiveExecutionRate,
        LocalDateTime lastCompletedAt) {

    public static RoleScenarioSlaSummaryData from(
            RoleScenarioSlaSummary summary,
            String spaceCode,
            String environment) {
        return new RoleScenarioSlaSummaryData(
                summary.spaceId(),
                spaceCode,
                environment,
                summary.agentAppCode(),
                summary.rolePackageCode(),
                summary.scenarioCode(),
                summary.totalRuns(),
                summary.successfulRuns(),
                summary.slaMetRuns(),
                summary.slaMetRate(),
                summary.averageDurationMs(),
                summary.proactiveExecutionRate(),
                summary.lastCompletedAt());
    }
}
