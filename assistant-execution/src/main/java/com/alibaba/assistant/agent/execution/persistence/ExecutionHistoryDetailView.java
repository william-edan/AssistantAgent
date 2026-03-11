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

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read model for execution run detail pages.
 */
public record ExecutionHistoryDetailView(
        String runId,
        String artifactCode,
        String artifactType,
        Long spaceId,
        String platformPrincipalId,
        String threadId,
        String status,
        String pausedStepId,
        String approvalRequestId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ExecutionStepView> steps) {
}
