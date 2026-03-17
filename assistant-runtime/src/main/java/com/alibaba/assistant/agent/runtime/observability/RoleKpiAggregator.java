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
package com.alibaba.assistant.agent.runtime.observability;

import com.alibaba.assistant.agent.execution.persistence.ExecutionTrace;
import com.alibaba.assistant.agent.execution.persistence.ExecutionTraceService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Aggregates persisted execution traces into scenario KPIs and SLA summaries.
 */
@Component
public class RoleKpiAggregator {

    private final ExecutionTraceService executionTraceService;

    private final SlaEvaluator slaEvaluator;

    public RoleKpiAggregator(
            ExecutionTraceService executionTraceService,
            SlaEvaluator slaEvaluator) {
        this.executionTraceService = executionTraceService;
        this.slaEvaluator = slaEvaluator;
    }

    public Optional<RoleScenarioSlaSummary> summarizeRoleScenario(
            Long spaceId,
            String agentAppCode,
            String rolePackageCode,
            String scenarioCode,
            LocalDateTime startedAfter,
            LocalDateTime startedBefore) {
        List<ExecutionTrace> traces = executionTraceService.listByRoleScenario(
                spaceId,
                agentAppCode,
                rolePackageCode,
                scenarioCode,
                startedAfter,
                startedBefore);
        if (traces.isEmpty()) {
            return Optional.empty();
        }
        int totalRuns = traces.size();
        int successfulRuns = (int) traces.stream()
                .filter(trace -> "COMPLETED".equalsIgnoreCase(trace.getStatus()))
                .count();
        int slaMetRuns = (int) traces.stream()
                .filter(slaEvaluator::isMet)
                .count();
        long averageDurationMs = Math.round(traces.stream()
                .map(ExecutionTrace::getDurationMs)
                .filter(duration -> duration != null && duration > 0)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0D));
        double proactiveExecutionRate = traces.stream()
                .filter(trace -> Boolean.TRUE.equals(trace.getProactive()))
                .count() / (double) totalRuns;
        LocalDateTime lastCompletedAt = traces.stream()
                .map(ExecutionTrace::getCompletedAt)
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return Optional.of(new RoleScenarioSlaSummary(
                spaceId,
                agentAppCode,
                rolePackageCode,
                scenarioCode,
                totalRuns,
                successfulRuns,
                slaMetRuns,
                totalRuns == 0 ? 0D : slaMetRuns / (double) totalRuns,
                averageDurationMs,
                proactiveExecutionRate,
                lastCompletedAt));
    }
}
