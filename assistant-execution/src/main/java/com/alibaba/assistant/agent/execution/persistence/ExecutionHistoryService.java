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
package com.alibaba.assistant.agent.execution.persistence;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read facade for persisted execution history.
 */
@Service
public class ExecutionHistoryService {

    private final ExecutionRunService executionRunService;

    private final ExecutionStepService executionStepService;

    public ExecutionHistoryService(
            ExecutionRunService executionRunService,
            ExecutionStepService executionStepService) {
        this.executionRunService = executionRunService;
        this.executionStepService = executionStepService;
    }

    /**
     * Load a persisted execution run and its steps.
     */
    public Optional<ExecutionHistoryDetailView> findDetailByRunId(String runId) {
        if (!StringUtils.hasText(runId)) {
            return Optional.empty();
        }
        return executionRunService.findLatestByRunId(runId.trim())
                .map(run -> new ExecutionHistoryDetailView(
                        run.getRunId(),
                        run.getArtifactCode(),
                        run.getArtifactType(),
                        run.getSpaceId(),
                        run.getPlatformPrincipalId(),
                        run.getThreadId(),
                        run.getStatus(),
                        run.getPausedStepId(),
                        run.getApprovalRequestId(),
                        run.getStartedAt(),
                        run.getCompletedAt(),
                        mapSteps(executionStepService.listByRunId(run.getRunId()))));
    }

    /**
     * List persisted execution runs for a space with optional exact filters.
     */
    public List<ExecutionHistoryRunSummaryView> listRuns(
            Long spaceId,
            String runId,
            String status,
            String artifactCode,
            String platformPrincipalId,
            String threadId,
            LocalDateTime startedAfter,
            LocalDateTime startedBefore,
            Integer limit) {
        if (spaceId == null) {
            return List.of();
        }
        return executionRunService.listBySpace(
                        spaceId,
                        normalizeOptional(runId),
                        normalizeOptional(status),
                        normalizeOptional(artifactCode),
                        normalizeOptional(platformPrincipalId),
                        normalizeOptional(threadId),
                        startedAfter,
                        startedBefore,
                        limit)
                .stream()
                .map(run -> new ExecutionHistoryRunSummaryView(
                        run.getRunId(),
                        run.getArtifactCode(),
                        run.getArtifactType(),
                        run.getSpaceId(),
                        run.getPlatformPrincipalId(),
                        run.getThreadId(),
                        run.getStatus(),
                        run.getPausedStepId(),
                        run.getApprovalRequestId(),
                        run.getStartedAt(),
                        run.getCompletedAt()))
                .toList();
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<ExecutionStepView> mapSteps(List<ExecutionStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .map(step -> new ExecutionStepView(
                        step.getStepId(),
                        step.getStepName(),
                        step.getConnectorId(),
                        step.getAuthProfileCode(),
                        step.getStatus(),
                        step.getErrorMessage(),
                        step.getStartedAt(),
                        step.getCompletedAt()))
                .toList();
    }
}
