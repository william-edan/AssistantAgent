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
package com.alibaba.assistant.agent.runtime.compiler;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.model.JoinType;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles canonical ToolMeta records into runtime artifacts.
 */
@Component
public class ToolMetaRuntimeArtifactCompiler {

    private final FlowDefinitionConverter flowDefinitionConverter;

    private final ObjectMapper objectMapper;

    public ToolMetaRuntimeArtifactCompiler(
            FlowDefinitionConverter flowDefinitionConverter,
            ObjectMapper objectMapper) {
        this.flowDefinitionConverter = flowDefinitionConverter;
        this.objectMapper = objectMapper;
    }

    /**
     * Compile a single ToolMeta record into a runtime artifact consumed by artifact execution.
     */
    public RuntimeArtifact compile(ToolMeta toolMeta) {
        if (toolMeta == null || !StringUtils.hasText(toolMeta.getToolCode())) {
            throw new IllegalArgumentException("toolMeta must define toolCode");
        }

        FlowDefinition flowDefinition = flowDefinitionConverter.parseFromToolMeta(toolMeta);
        if (!flowDefinitionConverter.validate(flowDefinition)) {
            throw new IllegalStateException("Invalid ToolMeta flow definition: " + toolMeta.getToolCode());
        }

        RuntimeArtifact.ActionBinding actionBinding = new RuntimeArtifact.ActionBinding(
                toolMeta.getId(),
                toolMeta.getToolCode(),
                null,
                buildOperationBinding(toolMeta),
                null,
                null,
                null,
                toolMeta.getParameterSchema(),
                null,
                toolMeta.getRiskLevel(),
                null,
                resolveSideEffectLevel(toolMeta),
                toolMeta.getVersion());

        Map<String, RuntimeArtifact.StepBinding> steps = buildStepBindings(flowDefinition, actionBinding);
        RuntimeArtifact.Interaction interaction = new RuntimeArtifact.Interaction(
                null,
                toolMeta.getToolCode(),
                toolMeta.getParameterSchema(),
                toolMeta.getInteractionPolicy(),
                null);

        return new RuntimeArtifact(
                null,
                toolMeta.getToolCode(),
                resolveArtifactType(flowDefinition),
                firstNonBlank(toolMeta.getToolName(), toolMeta.getToolCode()),
                toolMeta.getVersion(),
                null,
                null,
                null,
                null,
                interaction,
                flowDefinition,
                Map.of(toolMeta.getToolCode(), actionBinding),
                steps);
    }

    private Map<String, RuntimeArtifact.StepBinding> buildStepBindings(
            FlowDefinition flowDefinition,
            RuntimeArtifact.ActionBinding actionBinding) {
        Map<String, RuntimeArtifact.StepBinding> bindings = new LinkedHashMap<>();
        int order = 0;
        for (Map.Entry<String, StepDefinition> entry : flowDefinition.getSteps().entrySet()) {
            String stepId = entry.getKey();
            StepDefinition definition = entry.getValue();
            StepConfig config = definition != null ? definition.getConfig() : null;
            bindings.put(stepId, new RuntimeArtifact.StepBinding(
                    stepId,
                    definition != null ? definition.getName() : stepId,
                    definition != null && definition.getType() != null ? definition.getType().name() : "HTTP",
                    null,
                    actionBinding.actionCode(),
                    null,
                    null,
                    writeJson(config != null ? config.getInputMapping() : null),
                    writeJson(config != null ? config.getOutputMapping() : null),
                    writeJson(definition != null ? definition.getDependsOn() : null),
                    writeJson(config != null ? config.getConditions() : null),
                    writeJoinPolicy(definition != null ? definition.getJoinType() : null),
                    writeJson(config != null ? config.getApprovalGate() : null),
                    order++,
                    actionBinding));
        }
        return Map.copyOf(bindings);
    }

    private String buildOperationBinding(ToolMeta toolMeta) {
        Map<String, Object> binding = new LinkedHashMap<>();
        putIfHasText(binding, "method", toolMeta.getHttpMethod());
        putIfHasText(binding, "endpoint", toolMeta.getApiEndpoint());
        putIfHasText(binding, "contentType", toolMeta.getContentType());
        return writeJson(binding);
    }

    private RuntimeArtifact.ArtifactType resolveArtifactType(FlowDefinition flowDefinition) {
        return flowDefinition != null && flowDefinition.getSteps() != null && flowDefinition.getSteps().size() > 1
                ? RuntimeArtifact.ArtifactType.WORKFLOW
                : RuntimeArtifact.ArtifactType.ACTION;
    }

    private String writeJoinPolicy(JoinType joinType) {
        if (joinType == null) {
            return null;
        }
        return writeJson(Map.of("joinType", joinType.name()));
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize ToolMeta runtime artifact payload", ex);
        }
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (target == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }
        target.put(key, value.trim());
    }

    private String resolveSideEffectLevel(ToolMeta toolMeta) {
        if (toolMeta == null || !StringUtils.hasText(toolMeta.getCapabilityType())) {
            return "write";
        }
        return "READ".equalsIgnoreCase(toolMeta.getCapabilityType()) ? "read" : "write";
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
