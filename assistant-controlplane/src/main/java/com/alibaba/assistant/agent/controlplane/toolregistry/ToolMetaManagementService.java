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
package com.alibaba.assistant.agent.controlplane.toolregistry;

import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ToolMeta 控制面管理服务。
 */
@Service
public class ToolMetaManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";
    private static final String DEFAULT_TENANT = "default";
    private static final String DEFAULT_STATUS = "enabled";

    private final PlatformSpaceService platformSpaceService;
    private final ConnectorService connectorService;
    private final ToolMetaService toolMetaService;
    private final ToolMetaControlPlaneMapper mapper;

    public ToolMetaManagementService(
            PlatformSpaceService platformSpaceService,
            ConnectorService connectorService,
            ToolMetaService toolMetaService,
            ToolMetaControlPlaneMapper mapper) {
        this.platformSpaceService = platformSpaceService;
        this.connectorService = connectorService;
        this.toolMetaService = toolMetaService;
        this.mapper = mapper;
    }

    public List<ResolvedToolMetaManagementView> listTools(String spaceCode, String environment, String connectorCode, String keyword) {
        Optional<ConnectorResolution> resolution = resolveConnector(spaceCode, environment, connectorCode);
        if (resolution.isEmpty()) {
            return List.of();
        }
        ConnectorResolution connectorResolution = resolution.get();
        return toolMetaService.listEnabledByTenantAndSystem(DEFAULT_TENANT, connectorResolution.connector().getSystemCode()).stream()
                .filter(toolMeta -> mapper.matchesKeyword(toolMeta, keyword))
                .map(toolMeta -> mapper.toManagementView(
                        connectorResolution.space().getSpaceCode(),
                        connectorResolution.environment(),
                        connectorResolution.connector().getConnectorCode(),
                        toolMeta))
                .toList();
    }

    public Optional<ResolvedToolMetaManagementView> getTool(
            String spaceCode,
            String environment,
            String connectorCode,
            String toolCode) {
        Optional<ConnectorResolution> resolution = resolveConnector(spaceCode, environment, connectorCode);
        if (resolution.isEmpty() || !StringUtils.hasText(toolCode)) {
            return Optional.empty();
        }
        return toolMetaService.findLatestEnabledByToolCode(DEFAULT_TENANT, toolCode.trim())
                .filter(toolMeta -> matchesConnectorSystem(resolution.get().connector(), toolMeta))
                .map(toolMeta -> mapper.toManagementView(
                        resolution.get().space().getSpaceCode(),
                        resolution.get().environment(),
                        resolution.get().connector().getConnectorCode(),
                        toolMeta));
    }

    public Optional<ResolvedToolMetaManagementView> upsertTool(
            String spaceCode,
            String environment,
            String connectorCode,
            String toolCode,
            ToolMetaUpsertCommand command) {
        if (!StringUtils.hasText(toolCode) || command == null) {
            return Optional.empty();
        }
        Optional<ConnectorResolution> resolution = resolveConnector(spaceCode, environment, connectorCode);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        ConnectorResolution connectorResolution = resolution.get();
        ToolMeta target = toolMetaService.findLatestEnabledByToolCode(DEFAULT_TENANT, toolCode.trim())
                .orElseGet(ToolMeta::new);
        LocalDateTime now = LocalDateTime.now();
        boolean isNew = target.getId() == null;
        if (isNew) {
            target.setTenantId(DEFAULT_TENANT);
            target.setToolCode(toolCode.trim());
            target.setCreatedAt(now);
            target.setVersion(command.version() != null && command.version() > 0 ? command.version() : 1);
        }
        else {
            int nextVersion = command.version() != null && command.version() > 0
                    ? Math.max(command.version(), safeVersion(target.getVersion()) + 1)
                    : safeVersion(target.getVersion()) + 1;
            target.setVersion(nextVersion);
        }

        String normalizedToolType = mapper.normalizeToolType(
                firstNonBlank(command.toolType(), policyValue(command.interactionPolicy(), "toolType")));
        String normalizedVisibility = mapper.normalizeVisibility(
                firstNonBlank(command.visibility(), policyValue(command.interactionPolicy(), "visibility")),
                normalizedToolType);
        String normalizedInvocationPolicy = mapper.normalizeInvocationPolicy(
                firstNonBlank(command.invocationPolicy(), policyValue(command.interactionPolicy(), "invocationPolicy")),
                normalizedToolType,
                normalizedVisibility);
        if ("DEPENDENCY_ONLY".equalsIgnoreCase(normalizedInvocationPolicy)) {
            normalizedVisibility = "INTERNAL";
        }
        String normalizedExecutionMode = mapper.normalizeExecutionMode(
                firstNonBlank(command.executionMode(), policyValue(command.interactionPolicy(), "executionMode")),
                normalizedToolType);
        String normalizedRiskLevel = mapper.normalizeRiskLevel(command.riskLevel());
        boolean requiresConfirm = command.requiresConfirm() != null
                ? command.requiresConfirm()
                : (!"QUERY".equalsIgnoreCase(normalizedToolType) && isHighRisk(normalizedRiskLevel));
        Map<String, Object> parameterSchema = mapper.normalizeSchema(command.parameterSchema());
        Map<String, Object> interactionPolicy = normalizeInteractionPolicy(
                command.interactionPolicy(),
                normalizedToolType,
                normalizedVisibility,
                normalizedInvocationPolicy,
                normalizedExecutionMode,
                requiresConfirm);
        Map<String, Object> executionPlan = normalizeExecutionPlan(command, normalizedToolType, normalizedExecutionMode);

        target.setToolName(StringUtils.hasText(command.toolName()) ? command.toolName().trim() : toolCode.trim());
        target.setDescription(normalize(command.description()));
        target.setSystemCode(connectorResolution.connector().getSystemCode());
        target.setApiEndpoint(normalize(command.apiEndpoint()));
        target.setHttpMethod(StringUtils.hasText(command.httpMethod())
                ? command.httpMethod().trim().toUpperCase()
                : inferMethod(normalizedToolType));
        target.setContentType(StringUtils.hasText(command.contentType()) ? command.contentType().trim() : "application/json");
        target.setParameterSchema(mapper.serializeJson(parameterSchema));
        target.setExecutionPlan(mapper.serializeJson(executionPlan));
        target.setInteractionPolicy(mapper.serializeJson(interactionPolicy));
        target.setRiskLevel(normalizedRiskLevel);
        target.setRequiresAuth(command.requiresAuth() != null ? command.requiresAuth() : true);
        target.setRequiresConfirm(requiresConfirm);
        target.setCapabilityType(mapper.normalizeCapabilityType(command.capabilityType(), normalizedToolType));
        target.setStatus(StringUtils.hasText(command.status()) ? command.status().trim().toLowerCase() : DEFAULT_STATUS);
        target.setUpdatedAt(now);

        boolean persisted = isNew ? toolMetaService.save(target) : toolMetaService.updateById(target);
        if (!persisted) {
            return Optional.empty();
        }
        return Optional.of(mapper.toManagementView(
                connectorResolution.space().getSpaceCode(),
                connectorResolution.environment(),
                connectorResolution.connector().getConnectorCode(),
                target));
    }

    private Optional<ConnectorResolution> resolveConnector(String spaceCode, String environment, String connectorCode) {
        String normalizedSpaceCode = normalize(spaceCode);
        String normalizedEnvironment = normalizeEnvironment(environment);
        String normalizedConnectorCode = normalize(connectorCode);
        if (!StringUtils.hasText(normalizedSpaceCode) || !StringUtils.hasText(normalizedConnectorCode)) {
            return Optional.empty();
        }
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(normalizedSpaceCode, normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        return connectorService.findLatestActiveByCodeAndEnvironment(space.get().getId(), normalizedEnvironment, normalizedConnectorCode)
                .map(connector -> new ConnectorResolution(space.get(), connector, normalizedEnvironment));
    }

    private Map<String, Object> normalizeInteractionPolicy(
            Map<String, Object> input,
            String toolType,
            String visibility,
            String invocationPolicy,
            String executionMode,
            boolean requiresConfirm) {
        Map<String, Object> normalized = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();
        normalized.put("toolType", toolType);
        normalized.put("visibility", visibility);
        normalized.put("invocationPolicy", invocationPolicy);
        normalized.put("executionMode", executionMode);

        Map<String, Object> confirmPolicy = nestedMap(normalized.get("confirm"));
        confirmPolicy.put("required", requiresConfirm);
        normalized.put("confirm", Collections.unmodifiableMap(confirmPolicy));

        Map<String, Object> taskPolicy = nestedMap(normalized.get("task"));
        taskPolicy.put("enabled", "ASYNC".equalsIgnoreCase(executionMode));
        normalized.put("task", Collections.unmodifiableMap(taskPolicy));
        return Collections.unmodifiableMap(normalized);
    }

    private Map<String, Object> normalizeExecutionPlan(
            ToolMetaUpsertCommand command,
            String toolType,
            String executionMode) {
        if (command.executionPlan() != null && !command.executionPlan().isEmpty()) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(command.executionPlan()));
        }
        if (!StringUtils.hasText(command.apiEndpoint())) {
            throw new IllegalArgumentException("tool_definition_incomplete");
        }
        String stepId = "invoke";
        String method = StringUtils.hasText(command.httpMethod())
                ? command.httpMethod().trim().toUpperCase()
                : inferMethod(toolType);
        String contentType = StringUtils.hasText(command.contentType())
                ? command.contentType().trim()
                : "application/json";
        return Map.of(
                "version", "2.0",
                "mode", executionMode,
                "entry", List.of(stepId),
                "terminal", List.of(stepId),
                "steps", Map.of(stepId, Map.of(
                        "stepId", stepId,
                        "name", stepName(toolType, executionMode),
                        "type", "HTTP",
                        "config", Map.of(
                                "method", method,
                                "endpoint", command.apiEndpoint().trim(),
                                "contentType", contentType,
                                "successCondition", "$.code == 0",
                                "outputMapping", Map.of(
                                        "data", "$.data",
                                        "message", "$.msg")))));
    }

    private boolean matchesConnectorSystem(Connector connector, ToolMeta toolMeta) {
        return connector != null
                && toolMeta != null
                && StringUtils.hasText(connector.getSystemCode())
                && connector.getSystemCode().trim().equalsIgnoreCase(toolMeta.getSystemCode());
    }

    private String policyValue(Map<String, Object> interactionPolicy, String key) {
        if (interactionPolicy == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = interactionPolicy.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private String inferMethod(String toolType) {
        return "QUERY".equalsIgnoreCase(toolType) ? "GET" : "POST";
    }

    private String stepName(String toolType, String executionMode) {
        if ("ASYNC".equalsIgnoreCase(executionMode) || "AGENT_TASK".equalsIgnoreCase(toolType)) {
            return "submit_task";
        }
        return switch (toolType.toUpperCase()) {
            case "QUERY" -> "query";
            case "WORKFLOW" -> "execute_workflow";
            default -> "execute_action";
        };
    }

    private Map<String, Object> nestedMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> copied = new LinkedHashMap<>();
            mapValue.forEach((key, nestedValue) -> {
                if (key != null) {
                    copied.put(String.valueOf(key), nestedValue);
                }
            });
            return copied;
        }
        return new LinkedHashMap<>();
    }

    private boolean isHighRisk(String riskLevel) {
        return "HIGH".equalsIgnoreCase(riskLevel) || "CRITICAL".equalsIgnoreCase(riskLevel);
    }

    private int safeVersion(Integer version) {
        return version != null && version > 0 ? version : 1;
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private record ConnectorResolution(PlatformSpace space, Connector connector, String environment) {
    }
}
