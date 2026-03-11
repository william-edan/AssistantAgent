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
package com.alibaba.assistant.agent.controlplane.workflow;

import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpec;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpecService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed control-plane facade for workflow management.
 */
@Service
public class WorkflowManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String DEFAULT_STATUS = "enabled";

    private final PlatformSpaceService platformSpaceService;

    private final WorkflowSpecService workflowSpecService;

    private final WorkflowStepService workflowStepService;

    private final InteractionSpecService interactionSpecService;

    private final ConnectorService connectorService;

    private final ObjectMapper objectMapper;

    public WorkflowManagementService(
            PlatformSpaceService platformSpaceService,
            WorkflowSpecService workflowSpecService,
            WorkflowStepService workflowStepService,
            InteractionSpecService interactionSpecService,
            ConnectorService connectorService,
            ObjectMapper objectMapper) {
        this.platformSpaceService = platformSpaceService;
        this.workflowSpecService = workflowSpecService;
        this.workflowStepService = workflowStepService;
        this.interactionSpecService = interactionSpecService;
        this.connectorService = connectorService;
        this.objectMapper = objectMapper;
    }

    public List<ResolvedWorkflowManagementView> listWorkflows(String spaceCode, String environment) {
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        if (resolution.isEmpty()) {
            return List.of();
        }
        return workflowSpecService.listEnabledBySpace(resolution.get().space().getId()).stream()
                .map(workflowSpec -> toResolved(resolution.get(), workflowSpec, workflowStepService.listEnabledByWorkflowId(workflowSpec.getId())))
                .toList();
    }

    public Optional<ResolvedWorkflowManagementView> upsertWorkflow(
            String spaceCode,
            String environment,
            String workflowCode,
            WorkflowSpecUpsertCommand command) {
        if (!StringUtils.hasText(workflowCode) || command == null) {
            return Optional.empty();
        }
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        SpaceResolution spaceResolution = resolution.get();
        Optional<InteractionSpec> interaction = interactionSpecService.findLatestEnabledByCode(
                spaceResolution.space().getId(), normalize(command.interactionCode()));
        if (interaction.isEmpty()) {
            return Optional.empty();
        }
        Optional<WorkflowSpec> existing = workflowSpecService.findLatestEnabledByCode(
                spaceResolution.space().getId(), workflowCode.trim());
        WorkflowSpec target = existing.orElseGet(WorkflowSpec::new);
        LocalDateTime now = LocalDateTime.now();
        if (target.getId() == null) {
            target.setSpaceId(spaceResolution.space().getId());
            target.setWorkflowCode(workflowCode.trim());
            target.setCreatedAt(now);
            target.setVersion(1);
        }
        else {
            target.setVersion(Math.max(1, target.getVersion() == null ? 1 : target.getVersion() + 1));
        }
        target.setDisplayName(normalize(command.displayName()));
        target.setInteractionSpecId(interaction.get().getId());
        target.setRiskAggregationPolicy(normalizeLower(command.riskAggregationPolicy()));
        target.setApprovalAggregationPolicy(normalizeLower(command.approvalAggregationPolicy()));
        target.setFailurePolicyJson(serializeJson(command.failurePolicy()));
        target.setAuditPolicyJson(serializeJson(command.auditPolicy()));
        target.setStatus(normalizeLower(StringUtils.hasText(command.status()) ? command.status() : DEFAULT_STATUS));
        target.setUpdatedAt(now);

        boolean persisted = target.getId() == null ? workflowSpecService.save(target) : workflowSpecService.updateById(target);
        if (!persisted) {
            return Optional.empty();
        }
        Long workflowId = target.getId();
        if (workflowId == null) {
            workflowId = workflowSpecService.findLatestEnabledByCode(spaceResolution.space().getId(), workflowCode.trim())
                    .map(WorkflowSpec::getId)
                    .orElse(null);
        }
        if (workflowId == null) {
            return Optional.empty();
        }

        if (target.getId() == null) {
            target.setId(workflowId);
        }

        if (target.getId() != null) {
            LambdaQueryWrapper<WorkflowStep> removeQuery = new LambdaQueryWrapper<>();
            removeQuery.eq(WorkflowStep::getWorkflowId, workflowId);
            workflowStepService.remove(removeQuery);
        }

        List<ResolvedWorkflowStepManagementView> resolvedSteps = persistWorkflowSteps(spaceResolution, workflowId, command.steps(), now);
        if (resolvedSteps == null) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedWorkflowManagementView(
                workflowId,
                spaceResolution.space().getSpaceCode(),
                spaceResolution.environment(),
                target.getWorkflowCode(),
                target.getDisplayName(),
                interaction.get().getInteractionCode(),
                target.getRiskAggregationPolicy(),
                target.getApprovalAggregationPolicy(),
                parseMap(target.getFailurePolicyJson()),
                parseMap(target.getAuditPolicyJson()),
                target.getStatus(),
                resolvedSteps));
    }

    private List<ResolvedWorkflowStepManagementView> persistWorkflowSteps(
            SpaceResolution resolution,
            Long workflowId,
            List<WorkflowStepUpsertCommand> commands,
            LocalDateTime now) {
        List<WorkflowStepUpsertCommand> normalizedCommands = commands == null ? List.of() : commands;
        if (normalizedCommands.isEmpty()) {
            return List.of();
        }
        List<WorkflowStep> rows = new ArrayList<>();
        List<ResolvedWorkflowStepManagementView> resolvedViews = new ArrayList<>();
        for (WorkflowStepUpsertCommand command : normalizedCommands) {
            if (command == null || !StringUtils.hasText(command.stepId())) {
                return null;
            }
            Connector connector = null;
            if (StringUtils.hasText(command.connectorCode())) {
                Optional<Connector> resolvedConnector = connectorService.findLatestActiveByCodeAndEnvironment(
                        resolution.space().getId(), resolution.environment(), command.connectorCode().trim());
                if (resolvedConnector.isEmpty()) {
                    return null;
                }
                connector = resolvedConnector.get();
            }
            WorkflowStep row = new WorkflowStep();
            row.setWorkflowId(workflowId);
            row.setStepId(command.stepId().trim());
            row.setStepName(normalize(command.stepName()));
            row.setStepType(normalizeUpper(command.stepType()));
            row.setConnectorId(connector == null ? null : connector.getId());
            row.setTargetRef(normalize(command.targetRef()));
            row.setAllowedAuthProfilesJson(serializeJson(command.allowedAuthProfiles()));
            row.setBindingStrategiesJson(serializeJson(command.bindingStrategies()));
            row.setInputMappingJson(serializeJson(command.inputMapping()));
            row.setOutputMappingJson(serializeJson(command.outputMapping()));
            row.setDependsOnJson(serializeJson(command.dependsOn()));
            row.setConditionJson(serializeJson(command.condition()));
            row.setJoinPolicyJson(serializeJson(command.joinPolicy()));
            row.setRetryPolicyJson(serializeJson(command.retryPolicy()));
            row.setTimeoutPolicyJson(serializeJson(command.timeoutPolicy()));
            row.setApprovalGateJson(serializeJson(command.approvalGate()));
            row.setCompensationTargetRef(normalize(command.compensationTargetRef()));
            row.setResumePolicyJson(serializeJson(command.resumePolicy()));
            row.setStepOrder(command.stepOrder());
            row.setStatus(normalizeLower(StringUtils.hasText(command.status()) ? command.status() : DEFAULT_STATUS));
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            rows.add(row);
            resolvedViews.add(new ResolvedWorkflowStepManagementView(
                    row.getStepId(),
                    row.getStepName(),
                    row.getStepType(),
                    connector == null ? null : connector.getConnectorCode(),
                    row.getTargetRef(),
                    command.allowedAuthProfiles() == null ? List.of() : command.allowedAuthProfiles(),
                    command.bindingStrategies() == null ? List.of() : command.bindingStrategies(),
                    command.inputMapping() == null ? Map.of() : command.inputMapping(),
                    command.outputMapping() == null ? Map.of() : command.outputMapping(),
                    command.dependsOn() == null ? List.of() : command.dependsOn(),
                    command.condition() == null ? Map.of() : command.condition(),
                    command.joinPolicy() == null ? Map.of() : command.joinPolicy(),
                    command.retryPolicy() == null ? Map.of() : command.retryPolicy(),
                    command.timeoutPolicy() == null ? Map.of() : command.timeoutPolicy(),
                    command.approvalGate() == null ? Map.of() : command.approvalGate(),
                    row.getCompensationTargetRef(),
                    command.resumePolicy() == null ? Map.of() : command.resumePolicy(),
                    row.getStepOrder(),
                    row.getStatus()));
        }
        boolean saved = workflowStepService.saveBatch(rows);
        return saved ? resolvedViews : null;
    }

    private Optional<SpaceResolution> resolveSpace(String spaceCode, String environment) {
        String normalizedSpaceCode = normalize(spaceCode);
        String normalizedEnvironment = normalizeEnvironment(environment);
        if (!StringUtils.hasText(normalizedSpaceCode)) {
            return Optional.empty();
        }
        return platformSpaceService.findActiveByCode(normalizedSpaceCode, normalizedEnvironment)
                .map(space -> new SpaceResolution(space, normalizedEnvironment));
    }

    private ResolvedWorkflowManagementView toResolved(
            SpaceResolution resolution,
            WorkflowSpec workflowSpec,
            List<WorkflowStep> workflowSteps) {
        InteractionSpec interactionSpec = workflowSpec.getInteractionSpecId() == null ? null : interactionSpecService.getById(workflowSpec.getInteractionSpecId());
        return new ResolvedWorkflowManagementView(
                workflowSpec.getId(),
                resolution.space().getSpaceCode(),
                resolution.environment(),
                workflowSpec.getWorkflowCode(),
                workflowSpec.getDisplayName(),
                interactionSpec == null ? null : interactionSpec.getInteractionCode(),
                workflowSpec.getRiskAggregationPolicy(),
                workflowSpec.getApprovalAggregationPolicy(),
                parseMap(workflowSpec.getFailurePolicyJson()),
                parseMap(workflowSpec.getAuditPolicyJson()),
                workflowSpec.getStatus(),
                workflowSteps == null ? List.of() : workflowSteps.stream().map(this::toResolvedStep).toList());
    }

    private ResolvedWorkflowStepManagementView toResolvedStep(WorkflowStep workflowStep) {
        Connector connector = workflowStep.getConnectorId() == null ? null : connectorService.getById(workflowStep.getConnectorId());
        return new ResolvedWorkflowStepManagementView(
                workflowStep.getStepId(),
                workflowStep.getStepName(),
                workflowStep.getStepType(),
                connector == null ? null : connector.getConnectorCode(),
                workflowStep.getTargetRef(),
                parseList(workflowStep.getAllowedAuthProfilesJson()),
                parseList(workflowStep.getBindingStrategiesJson()),
                parseMap(workflowStep.getInputMappingJson()),
                parseMap(workflowStep.getOutputMappingJson()),
                parseList(workflowStep.getDependsOnJson()),
                parseMap(workflowStep.getConditionJson()),
                parseMap(workflowStep.getJoinPolicyJson()),
                parseMap(workflowStep.getRetryPolicyJson()),
                parseMap(workflowStep.getTimeoutPolicyJson()),
                parseMap(workflowStep.getApprovalGateJson()),
                workflowStep.getCompensationTargetRef(),
                parseMap(workflowStep.getResumePolicyJson()),
                workflowStep.getStepOrder(),
                workflowStep.getStatus());
    }

    private List<String> parseList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return parsed != null ? parsed : List.of();
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return parsed != null ? parsed : Map.of();
        }
        catch (Exception ignored) {
            return Map.of();
        }
    }

    private String serializeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeLower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : null;
    }

    private String normalizeUpper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : null;
    }

    private record SpaceResolution(PlatformSpace space, String environment) {
    }
}
