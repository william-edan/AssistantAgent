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

import java.util.List;
import java.util.Optional;

/**
 * Read facade for persisted execution history.
 */
@Service
public class ExecutionHistoryService {

    private static final int DEFAULT_LIST_LIMIT = 20;

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
                        run.getStartedAt(),
                        run.getCompletedAt(),
                        mapSteps(executionStepService.listByRunId(run.getRunId()))));
    }

    /**
     * List persisted execution runs for a space with optional status and artifact filters.
     */
    public List<ExecutionHistoryRunSummaryView> listRuns(
            Long spaceId,
            String status,
            String artifactCode,
            Integer limit) {
        if (spaceId == null) {
            return List.of();
        }
        int normalizedLimit = normalizeLimit(limit);
        return executionRunService.lambdaQuery()
                .eq(ExecutionRun::getSpaceId, spaceId)
                .eq(StringUtils.hasText(status), ExecutionRun::getStatus, status != null ? status.trim() : null)
                .eq(StringUtils.hasText(artifactCode), ExecutionRun::getArtifactCode, artifactCode != null ? artifactCode.trim() : null)
                .orderByDesc(ExecutionRun::getStartedAt)
                .orderByDesc(ExecutionRun::getId)
                .list()
                .stream()
                .limit(normalizedLimit)
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

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, 100);
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
