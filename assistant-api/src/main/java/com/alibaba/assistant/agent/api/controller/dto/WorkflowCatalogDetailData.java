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

import com.alibaba.assistant.agent.controlplane.catalog.ResolvedWorkflowDetailView;

import java.util.List;

/**
 * Workflow detail payload.
 */
public record WorkflowCatalogDetailData(
        String spaceCode,
        String environment,
        String workflowCode,
        String displayName,
        Long interactionSpecId,
        String status,
        Integer version,
        List<WorkflowStepData> steps) {

    public static WorkflowCatalogDetailData from(ResolvedWorkflowDetailView resolved) {
        return new WorkflowCatalogDetailData(
                resolved.spaceCode(),
                resolved.environment(),
                resolved.workflowCode(),
                resolved.displayName(),
                resolved.interactionSpecId(),
                resolved.status(),
                resolved.version(),
                resolved.steps().stream().map(WorkflowStepData::from).toList());
    }
}
