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
package com.alibaba.assistant.agent.runtime.tool.react;

import com.alibaba.assistant.agent.common.util.StructuredValueSanitizer;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一确认态执行参数装配。
 * 将槽位显示语义与执行语义分离，确保默认值和计算字段都能参与执行计划映射。
 */
@Component
@Profile("migration")
public class ArtifactExecutionParamAssembler {

    private final ComputedFieldProcessor computedFieldProcessor;

    private final ObjectMapper objectMapper;

    public ArtifactExecutionParamAssembler(ComputedFieldProcessor computedFieldProcessor, ObjectMapper objectMapper) {
        this.computedFieldProcessor = computedFieldProcessor;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> assemble(
            ArtifactExecuteTool.Request request,
            ToolContext toolContext,
            List<SlotDefinition> slotDefinitions) {
        Map<String, Object> requestParams = sanitizeMap(request != null ? request.params : null);
        if (!Boolean.TRUE.equals(request != null ? request.confirmed : null)) {
            return requestParams;
        }

        Map<String, Object> collectedStateParams = readCollectedStateParams(toolContext);
        if ((slotDefinitions == null || slotDefinitions.isEmpty()) && collectedStateParams.isEmpty()) {
            return requestParams;
        }

        Map<String, SlotValue> assembledSlotValues = new LinkedHashMap<>();
        Map<String, SlotDefinition> definitionsByName = indexByName(slotDefinitions);
        seedCollectedSlotValues(assembledSlotValues, definitionsByName, collectedStateParams);
        overlayRequestSlotValues(assembledSlotValues, definitionsByName, requestParams);
        applyDefaultValues(assembledSlotValues, slotDefinitions);
        computedFieldProcessor.processComputedFields(slotDefinitions, assembledSlotValues, buildComputationMetadata(toolContext));

        Map<String, Object> finalParams = buildResolvedParams(slotDefinitions, assembledSlotValues);
        appendNonSchemaParams(finalParams, collectedStateParams, definitionsByName);
        appendNonSchemaParams(finalParams, requestParams, definitionsByName);
        return StructuredValueSanitizer.sanitizeMap(finalParams);
    }

    private Map<String, SlotDefinition> indexByName(List<SlotDefinition> slotDefinitions) {
        Map<String, SlotDefinition> definitionsByName = new LinkedHashMap<>();
        if (slotDefinitions == null || slotDefinitions.isEmpty()) {
            return definitionsByName;
        }
        for (SlotDefinition slotDefinition : slotDefinitions) {
            if (slotDefinition == null || !StringUtils.hasText(slotDefinition.getName())) {
                continue;
            }
            definitionsByName.put(slotDefinition.getName(), slotDefinition);
        }
        return definitionsByName;
    }

    private void seedCollectedSlotValues(
            Map<String, SlotValue> assembledSlotValues,
            Map<String, SlotDefinition> definitionsByName,
            Map<String, Object> sourceValues) {
        if (assembledSlotValues == null || definitionsByName == null || definitionsByName.isEmpty()
                || sourceValues == null || sourceValues.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SlotDefinition> entry : definitionsByName.entrySet()) {
            Object value = sourceValues.get(entry.getKey());
            if (value == null) {
                continue;
            }
            assembledSlotValues.put(entry.getKey(), SlotValue.fromUser(entry.getKey(), value));
        }
    }

    private void overlayRequestSlotValues(
            Map<String, SlotValue> assembledSlotValues,
            Map<String, SlotDefinition> definitionsByName,
            Map<String, Object> requestParams) {
        if (assembledSlotValues == null || definitionsByName == null || definitionsByName.isEmpty()
                || requestParams == null || requestParams.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : requestParams.entrySet()) {
            if (!definitionsByName.containsKey(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            assembledSlotValues.put(entry.getKey(), SlotValue.fromUser(entry.getKey(), entry.getValue()));
        }
    }

    private void applyDefaultValues(Map<String, SlotValue> assembledSlotValues, List<SlotDefinition> slotDefinitions) {
        if (assembledSlotValues == null || slotDefinitions == null || slotDefinitions.isEmpty()) {
            return;
        }
        for (SlotDefinition slotDefinition : slotDefinitions) {
            if (slotDefinition == null || !StringUtils.hasText(slotDefinition.getName())) {
                continue;
            }
            if (assembledSlotValues.containsKey(slotDefinition.getName()) || slotDefinition.getDefaultValue() == null) {
                continue;
            }
            assembledSlotValues.put(
                    slotDefinition.getName(),
                    SlotValue.fromUser(slotDefinition.getName(), slotDefinition.getDefaultValue()));
        }
    }

    private Map<String, Object> buildResolvedParams(
            List<SlotDefinition> slotDefinitions,
            Map<String, SlotValue> assembledSlotValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (slotDefinitions == null || slotDefinitions.isEmpty()) {
            assembledSlotValues.forEach((key, value) -> params.put(key, value.getResolvedValue()));
            return params;
        }
        for (SlotDefinition slotDefinition : slotDefinitions) {
            if (slotDefinition == null || !StringUtils.hasText(slotDefinition.getName())) {
                continue;
            }
            SlotValue slotValue = assembledSlotValues.get(slotDefinition.getName());
            if (slotValue != null) {
                params.put(slotDefinition.getName(), slotValue.getResolvedValue());
                continue;
            }
            if (slotDefinition.getDefaultValue() != null) {
                params.put(slotDefinition.getName(), slotDefinition.getDefaultValue());
            }
        }
        return params;
    }

    private void appendNonSchemaParams(
            Map<String, Object> target,
            Map<String, Object> source,
            Map<String, SlotDefinition> definitionsByName) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            if (definitionsByName != null && definitionsByName.containsKey(entry.getKey())) {
                continue;
            }
            target.put(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, Object> readCollectedStateParams(ToolContext toolContext) {
        OverAllState state = getState(toolContext);
        if (state == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> rawCollected = state.value(AssistantStateKeys.COLLECTED_SLOTS, Map.class).orElse(null);
        if (rawCollected == null || rawCollected.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawCollected.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            Object resolvedValue = extractResolvedStateValue(entry.getValue());
            if (resolvedValue != null) {
                resolved.put(entry.getKey(), StructuredValueSanitizer.sanitize(resolvedValue));
            }
        }
        return resolved;
    }

    private Map<String, Object> buildComputationMetadata(ToolContext toolContext) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("current_date", resolveAnchorDate(toolContext).toString());
        return metadata;
    }

    private LocalDate resolveAnchorDate(ToolContext toolContext) {
        OverAllState state = getState(toolContext);
        String dateText = firstNonEmpty(
                readLooseStateText(state, "current_date"),
                readLooseStateText(state, "currentDate"),
                readLooseStateText(state, "current_time"),
                readLooseStateText(state, "currentTime"),
                readLooseStateText(state, "now"));
        if (StringUtils.hasText(dateText)) {
            String normalized = dateText.trim();
            if (normalized.length() >= 10) {
                normalized = normalized.substring(0, 10);
            }
            try {
                return LocalDate.parse(normalized);
            }
            catch (DateTimeParseException ignored) {
                // 回退到当前日期，避免执行主链因为元数据脏值失败。
            }
        }
        return LocalDate.now();
    }

    private String readLooseStateText(OverAllState state, String key) {
        if (state == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object rawValue = state.value(key, Object.class).orElse(null);
        if (rawValue == null) {
            return null;
        }
        String text = String.valueOf(rawValue).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstNonEmpty(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private Object extractResolvedStateValue(Object rawValue) {
        if (rawValue instanceof SlotValue slotValue) {
            return slotValue.getResolvedValue();
        }
        if (rawValue instanceof Map<?, ?> rawMap) {
            Object resolvedValue = rawMap.get("resolvedValue");
            if (resolvedValue != null) {
                return resolvedValue;
            }
            try {
                return objectMapper.convertValue(rawValue, SlotValue.class).getResolvedValue();
            }
            catch (Exception ignored) {
                return rawValue;
            }
        }
        return rawValue;
    }

    private OverAllState getState(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object stateObject = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return stateObject instanceof OverAllState ? (OverAllState) stateObject : null;
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> params) {
        return params != null ? StructuredValueSanitizer.sanitizeMap(params) : new LinkedHashMap<>();
    }
}
