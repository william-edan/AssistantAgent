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
package com.alibaba.assistant.agent.runtime.tool.codeact;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Artifact-native factory that publishes tools directly from runtime artifacts.
 */
@Component
public class ArtifactToolFactory {

    private final ObjectMapper objectMapper;

    private final ArtifactRuntimeExecutor artifactRuntimeExecutor;

    public ArtifactToolFactory(ObjectMapper objectMapper, ArtifactRuntimeExecutor artifactRuntimeExecutor) {
        this.objectMapper = objectMapper;
        this.artifactRuntimeExecutor = artifactRuntimeExecutor;
    }
    @Deprecated
    public ArtifactToolFactory(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    /**
     * Create artifact-backed tools from published descriptors.
     */
    public List<CodeactTool> createTools(List<PublishedToolDescriptor> descriptors) {
        List<CodeactTool> tools = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (PublishedToolDescriptor descriptor : descriptors != null ? descriptors : List.<PublishedToolDescriptor>of()) {
            createTool(descriptor, usedNames).ifPresent(tools::add);
        }
        return List.copyOf(tools);
    }

    /**
     * Create a single artifact-backed tool while respecting externally reserved names.
     */
    public Optional<CodeactTool> createTool(PublishedToolDescriptor descriptor, Set<String> usedNames) {
        if (descriptor == null || descriptor.artifact() == null) {
            return Optional.empty();
        }
        Set<String> effectiveUsedNames = usedNames != null ? usedNames : new HashSet<>();
        String toolName = ensureUniqueName(resolveToolName(descriptor), effectiveUsedNames);
        return Optional.of(new ArtifactBackedCodeactTool(
                objectMapper,
                descriptor,
                buildToolDefinition(descriptor, toolName),
                DefaultCodeactToolMetadata.builder()
                        .addSupportedLanguage(Language.PYTHON)
                        .targetClassName(resolveTargetClassName(descriptor))
                        .targetClassDescription(resolveTargetClassDescription(descriptor))
                        .codeInvocationTemplate(toolName + "(**kwargs) -> Dict[str, Any]")
                        .displayName(firstNonBlank(descriptor.displayName(), descriptor.artifact().getDisplayName(), descriptor.artifact().getArtifactCode()))
                        .addAlias(descriptor.artifact().getArtifactCode())
                        .alwaysAvailable(descriptor.alwaysAvailable())
                        .build(),
                artifactRuntimeExecutor));
    }

    private ToolDefinition buildToolDefinition(PublishedToolDescriptor descriptor, String toolName) {
        RuntimeArtifact artifact = descriptor.artifact();
        String description = firstNonBlank(
                descriptor.displayName(),
                artifact.getDisplayName(),
                artifact.getArtifactCode());
        return DefaultToolDefinition.builder()
                .name(toolName)
                .description(description)
                .inputSchema(resolveInputSchema(artifact))
                .build();
    }

    private String resolveInputSchema(RuntimeArtifact artifact) {
        if (artifact != null && artifact.getInteraction() != null
                && StringUtils.hasText(artifact.getInteraction().slotSchemaJson())) {
            String schema = toJsonSchemaFromSlots(artifact.getInteraction().slotSchemaJson());
            if (StringUtils.hasText(schema)) {
                return schema;
            }
        }
        if (artifact != null && !artifact.getActions().isEmpty()) {
            RuntimeArtifact.ActionBinding action = artifact.getActions().values().iterator().next();
            if (StringUtils.hasText(action.inputSchemaJson())) {
                return action.inputSchemaJson();
            }
        }
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    private String toJsonSchemaFromSlots(String slotSchemaJson) {
        try {
            JsonNode root = objectMapper.readTree(slotSchemaJson);
            JsonNode slots = root.get("slots");
            if (slots == null || !slots.isArray()) {
                return null;
            }
            JsonNode schema = objectMapper.createObjectNode();
            ((com.fasterxml.jackson.databind.node.ObjectNode) schema).put("type", "object");
            com.fasterxml.jackson.databind.node.ObjectNode properties =
                    ((com.fasterxml.jackson.databind.node.ObjectNode) schema).putObject("properties");
            com.fasterxml.jackson.databind.node.ArrayNode required =
                    ((com.fasterxml.jackson.databind.node.ObjectNode) schema).putArray("required");
            for (JsonNode slot : slots) {
                String name = text(slot, "name");
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                com.fasterxml.jackson.databind.node.ObjectNode property = properties.putObject(name);
                property.put("type", mapSlotType(text(slot, "type")));
                String description = firstNonBlank(text(slot, "title"), text(slot, "description"));
                if (StringUtils.hasText(description)) {
                    property.put("description", description);
                }
                if (slot.path("required").asBoolean(false)) {
                    required.add(name);
                }
            }
            if (required.isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) schema).remove("required");
            }
            return objectMapper.writeValueAsString(schema);
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private String mapSlotType(String slotType) {
        if (!StringUtils.hasText(slotType)) {
            return "string";
        }
        return switch (slotType.trim().toLowerCase(Locale.ROOT)) {
            case "int", "integer" -> "integer";
            case "float", "double", "number" -> "number";
            case "bool", "boolean" -> "boolean";
            case "array", "list" -> "array";
            default -> "string";
        };
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node != null ? node.get(fieldName) : null;
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private String resolveToolName(PublishedToolDescriptor descriptor) {
        String base = normalizeIdentifier(descriptor.artifact().getArtifactCode());
        if (!base.endsWith("_execute")) {
            base = base + "_execute";
        }
        return base;
    }

    private String resolveTargetClassName(PublishedToolDescriptor descriptor) {
        return firstNonBlank(descriptor.targetClassName(), normalizeIdentifier(descriptor.executionSystemCode()) + "_tools", "artifact_tools");
    }

    private String resolveTargetClassDescription(PublishedToolDescriptor descriptor) {
        return firstNonBlank(descriptor.targetClassDescription(), "Artifact-backed tools");
    }

    private String ensureUniqueName(String baseName, Set<String> usedNames) {
        String candidate = baseName;
        int idx = 2;
        while (usedNames.contains(candidate)) {
            candidate = baseName + "_" + idx++;
        }
        usedNames.add(candidate);
        return candidate;
    }

    private String normalizeIdentifier(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "artifact";
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_", "")
                .replaceAll("_$", "");
        if (!StringUtils.hasText(normalized)) {
            return "artifact";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            return "tool_" + normalized;
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}

