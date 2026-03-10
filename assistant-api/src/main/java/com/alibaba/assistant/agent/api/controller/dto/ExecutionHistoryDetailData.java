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

import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryDetailView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API payload for execution run detail pages.
 */
public record ExecutionHistoryDetailData(
        String runId,
        String artifactCode,
        String artifactType,
        Long spaceId,
        String spaceCode,
        String environment,
        String platformPrincipalId,
        String threadId,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ExecutionHistoryStepData> steps) {

    public static ExecutionHistoryDetailData from(
            ExecutionHistoryDetailView detailView,
            String spaceCode,
            String environment) {
        return new ExecutionHistoryDetailData(
                detailView.runId(),
                detailView.artifactCode(),
                detailView.artifactType(),
                detailView.spaceId(),
                spaceCode,
                environment,
                detailView.platformPrincipalId(),
                detailView.threadId(),
                detailView.status(),
                detailView.startedAt(),
                detailView.completedAt(),
                detailView.steps() != null
                        ? detailView.steps().stream().map(ExecutionHistoryStepData::from).toList()
                        : List.of());
    }
}
