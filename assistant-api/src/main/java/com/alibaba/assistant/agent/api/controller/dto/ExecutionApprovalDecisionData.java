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

import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalDecisionView;

import java.time.LocalDateTime;

/**
 * API payload for approval decision results.
 */
public record ExecutionApprovalDecisionData(
        String requestId,
        String runId,
        String artifactCode,
        String artifactType,
        Long spaceId,
        String spaceCode,
        String environment,
        String stepId,
        String status,
        String runStatus,
        String approvalChannel,
        String approverPrincipalId,
        String platformPrincipalId,
        LocalDateTime requestedAt,
        LocalDateTime respondedAt) {

    public static ExecutionApprovalDecisionData from(ExecutionApprovalDecisionView view) {
        return new ExecutionApprovalDecisionData(
                view.requestId(),
                view.runId(),
                view.artifactCode(),
                view.artifactType(),
                view.spaceId(),
                view.spaceCode(),
                view.environment(),
                view.stepId(),
                view.status(),
                view.runStatus(),
                view.approvalChannel(),
                view.approverPrincipalId(),
                view.platformPrincipalId(),
                view.requestedAt(),
                view.respondedAt());
    }
}
