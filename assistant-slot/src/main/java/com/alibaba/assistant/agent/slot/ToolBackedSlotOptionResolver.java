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
package com.alibaba.assistant.agent.slot;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.ToolOptionResolverConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves slot options through canonical query tools stored in {@code tool_meta}.
 */
@Service
public class ToolBackedSlotOptionResolver {

    private final ToolMetaService toolMetaService;

    private final HttpStepExecutor httpStepExecutor;

    private final ObjectMapper objectMapper;

    public ToolBackedSlotOptionResolver(
            ToolMetaService toolMetaService,
            HttpStepExecutor httpStepExecutor,
            ObjectMapper objectMapper) {
        this.toolMetaService = toolMetaService;
        this.httpStepExecutor = httpStepExecutor;
        this.objectMapper = objectMapper;
    }

    public List<SlotOption> resolve(SlotDefinition slot, String defaultSystemCode, String assistantUid) {
        if (slot == null || slot.getOptions() == null || slot.getOptions().getToolConfig() == null) {
            return List.of();
        }
        ToolOptionResolverConfig config = slot.getOptions().getToolConfig();
        if (!StringUtils.hasText(config.getToolCode())) {
            throw new IllegalStateException("tool_option_config_missing_tool_code");
        }

        ToolMeta toolMeta = toolMetaService.findLatestEnabledByToolCode(null, config.getToolCode().trim())
                .orElseThrow(() -> new IllegalStateException("tool_option_tool_not_found: " + config.getToolCode()));
        if (!"QUERY".equalsIgnoreCase(inferToolType(toolMeta))) {
            throw new IllegalStateException("tool_option_tool_must_be_query: " + config.getToolCode());
        }

        QueryStep queryStep = resolveQueryStep(toolMeta);
        String resolvedSystemCode = firstText(toolMeta.getSystemCode(), defaultSystemCode);
        FlowContext context = buildContext(queryStep.stepId(), resolvedSystemCode, assistantUid);
        StepResult stepResult = httpStepExecutor.execute(queryStep.stepConfig(), context);
        if (!stepResult.isSuccess()) {
            throw new IllegalStateException(firstText(stepResult.getErrorMessage(), "tool_option_query_failed"));
        }

        Object resultRoot = navigate(stepResult.getOutputs(), config.getResultPath());
        return toOptions(resultRoot, config);
    }

    private FlowContext buildContext(String stepId, String systemCode, String assistantUid) {
        Map<String, Object> initialInputs = new LinkedHashMap<>();
        if (StringUtils.hasText(systemCode)) {
            initialInputs.put("system_code", systemCode.trim());
            initialInputs.put("systemCode", systemCode.trim());
        }
        if (StringUtils.hasText(assistantUid)) {
            initialInputs.put("assistant_uid", assistantUid.trim());
            initialInputs.put("assistantUid", assistantUid.trim());
        }
        FlowContext context = new FlowContext(initialInputs);
        context.setCurrentStepId(stepId);
        context.setSystemCode(systemCode);
        context.setAssistantUid(assistantUid);
        return context;
    }

    @SuppressWarnings("unchecked")
    private QueryStep resolveQueryStep(ToolMeta toolMeta) {
        if (!StringUtils.hasText(toolMeta.getExecutionPlan())) {
            throw new IllegalStateException("tool_option_execution_plan_missing: " + toolMeta.getToolCode());
        }
        try {
            Map<String, Object> executionPlan = objectMapper.readValue(toolMeta.getExecutionPlan(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            Map<String, Object> steps = asMap(executionPlan.get("steps"));
            if (steps.isEmpty()) {
                throw new IllegalStateException("tool_option_steps_missing: " + toolMeta.getToolCode());
            }
            String stepId = resolveEntryStepId(executionPlan, steps);
            Map<String, Object> step = asMap(steps.get(stepId));
            String stepType = firstText(step.get("type"));
            if (!"HTTP".equalsIgnoreCase(stepType)) {
                throw new IllegalStateException("tool_option_http_step_required: " + toolMeta.getToolCode());
            }
            StepConfig stepConfig = objectMapper.convertValue(asMap(step.get("config")), StepConfig.class);
            return new QueryStep(stepId, stepConfig);
        }
        catch (IllegalStateException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException("tool_option_execution_plan_invalid: " + toolMeta.getToolCode(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveEntryStepId(Map<String, Object> executionPlan, Map<String, Object> steps) {
        Object entryNode = executionPlan.get("entry");
        if (entryNode instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first != null && steps.containsKey(String.valueOf(first))) {
                return String.valueOf(first);
            }
        }
        return steps.keySet().iterator().next();
    }

    private Object navigate(Map<String, Object> outputs, String resultPath) {
        Object current = outputs != null ? outputs : Map.of();
        if (!StringUtils.hasText(resultPath)) {
            return current;
        }
        String normalizedPath = resultPath.trim();
        if (normalizedPath.startsWith("$.")) {
            normalizedPath = normalizedPath.substring(2);
        }
        else if (normalizedPath.startsWith("$")) {
            normalizedPath = normalizedPath.substring(1);
        }
        if (!StringUtils.hasText(normalizedPath)) {
            return current;
        }
        for (String segment : normalizedPath.split("\\.")) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            }
            else if (current instanceof List<?> list) {
                Integer index = parseIndex(segment);
                if (index == null || index < 0 || index >= list.size()) {
                    return List.of();
                }
                current = list.get(index);
            }
            else {
                return List.of();
            }
        }
        return current;
    }

    private List<SlotOption> toOptions(Object resultRoot, ToolOptionResolverConfig config) {
        if (!(resultRoot instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<SlotOption> options = new ArrayList<>();
        for (Object item : list) {
            SlotOption option = toOption(item, config);
            if (option != null) {
                options.add(option);
            }
        }
        return options;
    }

    @SuppressWarnings("unchecked")
    private SlotOption toOption(Object rawItem, ToolOptionResolverConfig config) {
        if (rawItem instanceof Map<?, ?> map) {
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) map);
            Object value = firstNonNull(item.get(config.getValueField()), item.get("value"), item.get("id"));
            String label = firstText(item.get(config.getLabelField()), item.get("label"), value);
            if (!StringUtils.hasText(label) || value == null) {
                return null;
            }
            SlotOption option = new SlotOption(label, value);
            option.setDescription(firstText(item.get(config.getDescriptionField()), item.get("description")));
            option.setDisabled(asBoolean(item.get(config.getDisabledField())));
            return option;
        }
        String text = firstText(rawItem);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return new SlotOption(text, text);
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private Integer parseIndex(String segment) {
        try {
            return Integer.parseInt(segment);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = firstText(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    @SuppressWarnings("unchecked")
    private String inferToolType(ToolMeta toolMeta) {
        if (toolMeta == null) {
            return "ACTION";
        }
        Map<String, Object> interactionPolicy = parseJsonMap(toolMeta.getInteractionPolicy());
        String explicitToolType = firstText(interactionPolicy.get("toolType"));
        if (StringUtils.hasText(explicitToolType)) {
            return explicitToolType.trim().toUpperCase();
        }
        Map<String, Object> executionPlan = parseJsonMap(toolMeta.getExecutionPlan());
        Object steps = executionPlan.get("steps");
        if (steps instanceof Map<?, ?> stepMap && stepMap.size() > 1) {
            return "WORKFLOW";
        }
        if ("READ".equalsIgnoreCase(toolMeta.getCapabilityType())) {
            return "QUERY";
        }
        return "ACTION";
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        }
        catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private record QueryStep(String stepId, StepConfig stepConfig) {
    }
}
