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
package com.alibaba.assistant.agent.controlplane.catalog;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpecService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStep;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStepService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Read facade for workflow catalog details.
 */
@Service
public class WorkflowCatalogService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final PlatformSpaceService platformSpaceService;
    private final WorkflowSpecService workflowSpecService;
    private final WorkflowStepService workflowStepService;

    public WorkflowCatalogService(
            PlatformSpaceService platformSpaceService,
            WorkflowSpecService workflowSpecService,
            WorkflowStepService workflowStepService) {
        this.platformSpaceService = platformSpaceService;
        this.workflowSpecService = workflowSpecService;
        this.workflowStepService = workflowStepService;
    }

    public Optional<ResolvedWorkflowDetailView> getWorkflowDetail(String spaceCode, String environment, String workflowCode) {
        if (!StringUtils.hasText(spaceCode) || !StringUtils.hasText(workflowCode)) {
            return Optional.empty();
        }
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(spaceCode.trim(), normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        Optional<WorkflowSpec> workflow = workflowSpecService.findLatestEnabledByCode(space.get().getId(), workflowCode.trim());
        if (workflow.isEmpty()) {
            return Optional.empty();
        }
        List<ResolvedWorkflowStepDetailView> steps = workflowStepService.listEnabledByWorkflowId(workflow.get().getId()).stream()
                .map(step -> new ResolvedWorkflowStepDetailView(
                        step.getStepId(),
                        step.getStepName(),
                        step.getStepType(),
                        step.getConnectorId(),
                        step.getTargetRef(),
                        step.getStepOrder(),
                        step.getDependsOnJson(),
                        step.getApprovalGateJson()))
                .toList();
        return Optional.of(new ResolvedWorkflowDetailView(
                space.get().getSpaceCode(),
                normalizedEnvironment,
                workflow.get().getWorkflowCode(),
                workflow.get().getDisplayName(),
                workflow.get().getInteractionSpecId(),
                workflow.get().getStatus(),
                workflow.get().getVersion(),
                steps));
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
