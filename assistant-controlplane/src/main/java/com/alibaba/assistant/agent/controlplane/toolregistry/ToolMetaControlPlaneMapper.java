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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ToolMeta 控制面映射与归一化工具。
 */
@Component
public class ToolMetaControlPlaneMapper {

    private final ObjectMapper objectMapper;

    public ToolMetaControlPlaneMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResolvedToolMetaManagementView toManagementView(
            String spaceCode,
            String environment,
            String connectorCode,
            ToolMeta toolMeta) {
        return new ResolvedToolMetaManagementView(
                toolMeta.getId(),
                spaceCode,
                spaceCode == null ? null : environment,
                connectorCode,
                toolMeta.getToolCode(),
                toolMeta.getToolName(),
                toolMeta.getDescription(),
                toolMeta.getSystemCode(),
                inferToolType(toolMeta),
                inferVisibility(toolMeta),
                inferInvocationPolicy(toolMeta),
                inferExecutionMode(toolMeta),
                toolMeta.getApiEndpoint(),
                normalizeHttpMethod(toolMeta.getHttpMethod()),
                toolMeta.getContentType(),
                parseMap(toolMeta.getParameterSchema()),
                parseMap(toolMeta.getExecutionPlan()),
                parseMap(toolMeta.getInteractionPolicy()),
                normalizeUpper(toolMeta.getRiskLevel()),
                defaultBoolean(toolMeta.getRequiresAuth(), true),
                defaultBoolean(toolMeta.getRequiresConfirm(), false),
                normalizeUpper(toolMeta.getCapabilityType()),
                toolMeta.getVersion(),
                normalizeLower(toolMeta.getStatus()));
    }

    public ResolvedToolMetaSummaryView toSummaryView(ToolMeta toolMeta) {
        return new ResolvedToolMetaSummaryView(
                toolMeta.getId(),
                toolMeta.getToolCode(),
                toolMeta.getToolName(),
                toolMeta.getSystemCode(),
                inferToolType(toolMeta),
                inferVisibility(toolMeta),
                inferInvocationPolicy(toolMeta),
                inferExecutionMode(toolMeta),
                normalizeUpper(toolMeta.getRiskLevel()),
                defaultBoolean(toolMeta.getRequiresConfirm(), false),
                normalizeLower(toolMeta.getStatus()),
                toolMeta.getVersion());
    }

    public ResolvedToolMetaDetailView toDetailView(String spaceCode, String environment, ToolMeta toolMeta) {
        return new ResolvedToolMetaDetailView(
                spaceCode,
                environment,
                toolMeta.getToolCode(),
                toolMeta.getToolName(),
                toolMeta.getDescription(),
                toolMeta.getSystemCode(),
                inferToolType(toolMeta),
                inferVisibility(toolMeta),
                inferInvocationPolicy(toolMeta),
                inferExecutionMode(toolMeta),
                toolMeta.getApiEndpoint(),
                normalizeHttpMethod(toolMeta.getHttpMethod()),
                toolMeta.getContentType(),
                parseMap(toolMeta.getParameterSchema()),
                parseMap(toolMeta.getExecutionPlan()),
                parseMap(toolMeta.getInteractionPolicy()),
                normalizeUpper(toolMeta.getRiskLevel()),
                defaultBoolean(toolMeta.getRequiresAuth(), true),
                defaultBoolean(toolMeta.getRequiresConfirm(), false),
                normalizeUpper(toolMeta.getCapabilityType()),
                normalizeLower(toolMeta.getStatus()),
                toolMeta.getVersion());
    }

    public boolean matchesKeyword(ToolMeta toolMeta, String keyword) {
        if (toolMeta == null || !StringUtils.hasText(keyword)) {
            return true;
        }
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(toolMeta.getToolCode(), normalizedKeyword)
                || contains(toolMeta.getToolName(), normalizedKeyword)
                || contains(toolMeta.getDescription(), normalizedKeyword)
                || contains(toolMeta.getSystemCode(), normalizedKeyword)
                || contains(toolMeta.getApiEndpoint(), normalizedKeyword)
                || contains(inferToolType(toolMeta), normalizedKeyword)
                || contains(inferVisibility(toolMeta), normalizedKeyword)
                || contains(inferInvocationPolicy(toolMeta), normalizedKeyword)
                || contains(inferExecutionMode(toolMeta), normalizedKeyword);
    }

    public Map<String, Object> parseMap(String json) {
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

    public String serializeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new IllegalStateException("tool_meta_json_serialize_failed", ex);
        }
    }

    public String inferToolType(ToolMeta toolMeta) {
        if (toolMeta == null) {
            return "ACTION";
        }
        Map<String, Object> interactionPolicy = parseMap(toolMeta.getInteractionPolicy());
        String explicitToolType = asText(interactionPolicy.get("toolType"));
        if (StringUtils.hasText(explicitToolType)) {
            return normalizeToolType(explicitToolType);
        }
        Map<String, Object> executionPlan = parseMap(toolMeta.getExecutionPlan());
        Object steps = executionPlan.get("steps");
        if (steps instanceof Map<?, ?> stepMap && stepMap.size() > 1) {
            return "WORKFLOW";
        }
        if ("READ".equalsIgnoreCase(toolMeta.getCapabilityType())) {
            return "QUERY";
        }
        return "ACTION";
    }

    public String inferVisibility(ToolMeta toolMeta) {
        if (toolMeta == null) {
            return "USER";
        }
        Map<String, Object> interactionPolicy = parseMap(toolMeta.getInteractionPolicy());
        String explicitVisibility = asText(interactionPolicy.get("visibility"));
        if (StringUtils.hasText(explicitVisibility)) {
            return normalizeVisibility(explicitVisibility, inferToolType(toolMeta));
        }
        String explicitInvocationPolicy = asText(interactionPolicy.get("invocationPolicy"));
        if ("DEPENDENCY_ONLY".equalsIgnoreCase(explicitInvocationPolicy)) {
            return "INTERNAL";
        }
        return defaultVisibilityFor(inferToolType(toolMeta));
    }

    public String inferInvocationPolicy(ToolMeta toolMeta) {
        if (toolMeta == null) {
            return "DIRECT";
        }
        Map<String, Object> interactionPolicy = parseMap(toolMeta.getInteractionPolicy());
        return normalizeInvocationPolicy(
                asText(interactionPolicy.get("invocationPolicy")),
                inferToolType(toolMeta),
                inferVisibility(toolMeta));
    }

    public String inferExecutionMode(ToolMeta toolMeta) {
        if (toolMeta == null) {
            return "SYNC";
        }
        Map<String, Object> interactionPolicy = parseMap(toolMeta.getInteractionPolicy());
        return normalizeExecutionMode(asText(interactionPolicy.get("executionMode")), inferToolType(toolMeta));
    }

    public String normalizeToolType(String rawToolType) {
        if (!StringUtils.hasText(rawToolType)) {
            return "ACTION";
        }
        String normalized = rawToolType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "QUERY", "ACTION", "WORKFLOW", "AGENT_TASK" -> normalized;
            default -> "ACTION";
        };
    }

    public String normalizeVisibility(String rawVisibility, String toolType) {
        if (!StringUtils.hasText(rawVisibility)) {
            return defaultVisibilityFor(toolType);
        }
        String normalized = rawVisibility.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "USER", "PLANNER", "INTERNAL" -> normalized;
            default -> defaultVisibilityFor(toolType);
        };
    }

    public String normalizeInvocationPolicy(String rawInvocationPolicy, String toolType, String visibility) {
        String normalizedVisibility = normalizeVisibility(visibility, toolType);
        if ("INTERNAL".equalsIgnoreCase(normalizedVisibility)) {
            return "DEPENDENCY_ONLY";
        }
        if (!StringUtils.hasText(rawInvocationPolicy)) {
            return defaultInvocationPolicyFor(toolType, normalizedVisibility);
        }
        String normalized = rawInvocationPolicy.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DIRECT", "COMPOSABLE" -> normalized;
            case "DEPENDENCY_ONLY" -> "DEPENDENCY_ONLY";
            default -> defaultInvocationPolicyFor(toolType, normalizedVisibility);
        };
    }

    public String normalizeExecutionMode(String rawExecutionMode, String toolType) {
        if ("AGENT_TASK".equalsIgnoreCase(toolType)) {
            return "ASYNC";
        }
        if (!StringUtils.hasText(rawExecutionMode)) {
            return defaultExecutionModeFor(toolType);
        }
        String normalized = rawExecutionMode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SYNC", "ASYNC" -> normalized;
            default -> defaultExecutionModeFor(toolType);
        };
    }

    public String normalizeRiskLevel(String riskLevel) {
        if (!StringUtils.hasText(riskLevel)) {
            return "LOW";
        }
        String normalized = riskLevel.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> "LOW";
        };
    }

    public String normalizeCapabilityType(String rawCapabilityType, String toolType) {
        if (StringUtils.hasText(rawCapabilityType)) {
            String normalized = rawCapabilityType.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "READ", "ACTION", "WRITE" -> normalized;
                default -> "QUERY".equalsIgnoreCase(toolType) ? "READ" : "ACTION";
            };
        }
        return "QUERY".equalsIgnoreCase(toolType) ? "READ" : "ACTION";
    }

    public Map<String, Object> normalizeSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        Map<String, Object> normalized = new LinkedHashMap<>(schema);
        normalized.putIfAbsent("type", "object");
        normalized.putIfAbsent("properties", Map.of());
        return Collections.unmodifiableMap(normalized);
    }

    private String defaultVisibilityFor(String rawToolType) {
        String toolType = normalizeToolType(rawToolType);
        return switch (toolType) {
            case "QUERY" -> "PLANNER";
            default -> "USER";
        };
    }

    private String defaultInvocationPolicyFor(String rawToolType, String rawVisibility) {
        if ("INTERNAL".equalsIgnoreCase(rawVisibility)) {
            return "DEPENDENCY_ONLY";
        }
        String toolType = normalizeToolType(rawToolType);
        return switch (toolType) {
            case "QUERY" -> "COMPOSABLE";
            default -> "DIRECT";
        };
    }

    private String defaultExecutionModeFor(String rawToolType) {
        String toolType = normalizeToolType(rawToolType);
        return "AGENT_TASK".equalsIgnoreCase(toolType) ? "ASYNC" : "SYNC";
    }

    private String normalizeHttpMethod(String rawHttpMethod) {
        if (!StringUtils.hasText(rawHttpMethod)) {
            return null;
        }
        return rawHttpMethod.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeUpper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String normalizeLower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
    }

    private boolean defaultBoolean(Boolean value, boolean defaultValue) {
        return value != null ? value : defaultValue;
    }

    private boolean contains(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
