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
package com.alibaba.assistant.agent.runtime.execution.batch;

import com.alibaba.assistant.agent.execution.batch.BatchItemSelector;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch selector implementation backed by the runtime dependency tool executor.
 */
public class ToolExecutorBatchItemSelector implements BatchItemSelector {

    private static final List<String> ITEM_ID_KEYS = List.of("id", "employee_id", "employeeId", "uid", "user_id");

    private final ObjectProvider<ToolExecutor> toolExecutorProvider;

    private final ObjectMapper objectMapper;

    public ToolExecutorBatchItemSelector(ObjectProvider<ToolExecutor> toolExecutorProvider, ObjectMapper objectMapper) {
        this.toolExecutorProvider = toolExecutorProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> selectItems(String selectorToolCode, Map<String, Object> arguments, FlowContext context) {
        ToolExecutor.ExecutionResult executionResult = executeDependency(selectorToolCode, arguments, context);
        if (executionResult == null || !executionResult.success()) {
            throw new IllegalStateException(resolveFailureMessage(selectorToolCode, executionResult));
        }
        Object payload = firstCandidate(executionResult.outputFields(), executionResult.payload());
        return applyBirthdayScopeFilter(normalizeItems(payload), arguments);
    }

    @Override
    public StepResult executeAction(String actionToolCode, Map<String, Object> arguments, FlowContext context) {
        ToolExecutor.ExecutionResult executionResult = executeDependency(actionToolCode, arguments, context);
        if (executionResult == null || !executionResult.success()) {
            return StepResult.failure(resolveFailureMessage(actionToolCode, executionResult));
        }
        Map<String, Object> outputs = new LinkedHashMap<>(executionResult.outputFields());
        if (outputs.isEmpty() && executionResult.payload() != null) {
            outputs.putAll(executionResult.payload());
        }
        return StepResult.success(outputs);
    }

    private ToolExecutor.ExecutionResult executeDependency(
            String toolCode,
            Map<String, Object> arguments,
            FlowContext context) {
        ToolExecutor toolExecutor = toolExecutorProvider != null ? toolExecutorProvider.getIfAvailable() : null;
        if (toolExecutor == null) {
            throw new IllegalStateException("Batch dependency ToolExecutor is unavailable for toolCode=" + toolCode);
        }
        Map<String, Object> safeArguments = arguments != null ? new LinkedHashMap<>(arguments) : Map.of();
        ToolContext toolContext = new ToolContext(buildToolContextMap(context));
        String tenantId = context != null ? context.getSystemCode() : null;
        return toolExecutor.execute(tenantId, toolCode, safeArguments, toolContext);
    }

    private Map<String, Object> buildToolContextMap(FlowContext context) {
        Map<String, Object> toolContext = new LinkedHashMap<>();
        if (context == null) {
            return toolContext;
        }
        toolContext.putAll(context.getInitialInputs());
        putIfHasText(toolContext, "systemCode", context.getSystemCode());
        putIfHasText(toolContext, "assistantUid", context.getAssistantUid());
        putIfHasText(toolContext, "threadId", context.getThreadId());
        putIfHasText(toolContext, "runId", context.getRunId());
        putIfHasText(toolContext, "currentStepId", context.getCurrentStepId());
        return toolContext;
    }

    private Object firstCandidate(Map<String, Object> outputFields, Map<String, Object> payload) {
        Object candidate = firstCandidate(outputFields);
        if (candidate != null) {
            return candidate;
        }
        candidate = firstCandidate(payload);
        if (candidate != null) {
            return candidate;
        }
        return null;
    }

    private Object firstCandidate(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        for (String key : List.of("data", "items", "list", "records", "rows", "result")) {
            if (source.containsKey(key) && source.get(key) != null) {
                return source.get(key);
            }
        }
        return source;
    }

    private List<Map<String, Object>> normalizeItems(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> normalizedItem = normalizeItem(item);
                if (!normalizedItem.isEmpty()) {
                    normalized.add(normalizedItem);
                }
            }
            return normalized;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> normalizedMap = toMap(map);
            Object nested = firstCandidate(normalizedMap);
            if (nested != null && nested != raw) {
                return normalizeItems(nested);
            }
            Map<String, Object> normalizedItem = normalizeItem(normalizedMap);
            return normalizedItem.isEmpty() ? List.of() : List.of(normalizedItem);
        }
        Map<String, Object> single = normalizeItem(raw);
        return single.isEmpty() ? List.of() : List.of(single);
    }

    private Map<String, Object> normalizeItem(Object item) {
        Map<String, Object> normalized = toMap(item);
        if (normalized.isEmpty() && item != null) {
            normalized.put("id", item);
        }
        normalizeItemId(normalized);
        return normalized;
    }

    private List<Map<String, Object>> applyBirthdayScopeFilter(List<Map<String, Object>> items, Map<String, Object> arguments) {
        String birthdayScope = resolveBirthdayScope(arguments);
        if (!StringUtils.hasText(birthdayScope) || items == null || items.isEmpty()) {
            return items != null ? items : List.of();
        }
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (matchesBirthdayScope(item, birthdayScope, today)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private String resolveBirthdayScope(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        Object value = arguments.get("birthday_scope");
        if (value == null) {
            value = arguments.get("birthdayScope");
        }
        return value != null ? String.valueOf(value).trim() : null;
    }

    private boolean matchesBirthdayScope(Map<String, Object> item, String birthdayScope, LocalDate today) {
        LocalDate birthday = parseBirthday(item);
        if (birthday == null) {
            return false;
        }
        if ("TODAY".equalsIgnoreCase(birthdayScope)) {
            return birthday.getMonthValue() == today.getMonthValue()
                    && birthday.getDayOfMonth() == today.getDayOfMonth();
        }
        if ("THIS_MONTH".equalsIgnoreCase(birthdayScope)) {
            return birthday.getMonthValue() == today.getMonthValue();
        }
        return true;
    }

    private LocalDate parseBirthday(Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        for (String key : List.of("birthday", "birthDate", "birth_date", "出生日期", "生日")) {
            LocalDate parsed = parseBirthdayValue(item.get(key));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private LocalDate parseBirthdayValue(Object rawValue) {
        if (rawValue instanceof LocalDate localDate) {
            return localDate;
        }
        if (rawValue == null) {
            return null;
        }
        String text = String.valueOf(rawValue).trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        List<Integer> numericParts = extractNumericParts(text);
        if (numericParts.size() >= 3) {
            return buildBirthdayDate(numericParts.get(numericParts.size() - 2), numericParts.get(numericParts.size() - 1));
        }
        if (numericParts.size() == 2) {
            return buildBirthdayDate(numericParts.get(0), numericParts.get(1));
        }
        return null;
    }

    private List<Integer> extractNumericParts(String text) {
        List<Integer> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (Character.isDigit(ch)) {
                current.append(ch);
                continue;
            }
            flushNumericPart(parts, current);
        }
        flushNumericPart(parts, current);
        return parts;
    }

    private void flushNumericPart(List<Integer> parts, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        try {
            parts.add(Integer.parseInt(current.toString()));
        }
        catch (NumberFormatException ignored) {
            // Ignore malformed birthday fragments and keep scanning.
        }
        current.setLength(0);
    }

    private LocalDate buildBirthdayDate(int month, int day) {
        LocalDate today = LocalDate.now();
        try {
            return LocalDate.of(today.getYear(), month, day);
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> toMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = objectMapper.convertValue(value, Map.class);
            return converted != null ? new LinkedHashMap<>(converted) : new LinkedHashMap<>();
        }
        catch (Exception ignored) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("id", value);
            return fallback;
        }
    }

    private void normalizeItemId(Map<String, Object> item) {
        if (item == null) {
            return;
        }
        Object currentId = item.get("id");
        if (currentId != null && StringUtils.hasText(String.valueOf(currentId))) {
            return;
        }
        for (String key : ITEM_ID_KEYS) {
            Object candidate = item.get(key);
            if (candidate != null && StringUtils.hasText(String.valueOf(candidate))) {
                item.put("id", candidate);
                return;
            }
        }
    }

    private String resolveFailureMessage(String toolCode, ToolExecutor.ExecutionResult executionResult) {
        if (executionResult != null && StringUtils.hasText(executionResult.errorMessage())) {
            return executionResult.errorMessage();
        }
        return "Batch dependency execution failed for toolCode=" + toolCode;
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }
}
